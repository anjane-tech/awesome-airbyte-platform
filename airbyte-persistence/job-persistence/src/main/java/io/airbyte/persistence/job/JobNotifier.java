/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.persistence.job;

import static io.airbyte.metrics.lib.MetricTags.NOTIFICATION_CLIENT;
import static io.airbyte.metrics.lib.MetricTags.NOTIFICATION_TRIGGER;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableMap.Builder;
import io.airbyte.analytics.TrackingClient;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.config.ActorDefinitionVersion;
import io.airbyte.config.DestinationConnection;
import io.airbyte.config.Notification.NotificationType;
import io.airbyte.config.NotificationItem;
import io.airbyte.config.NotificationSettings;
import io.airbyte.config.SourceConnection;
import io.airbyte.config.StandardDestinationDefinition;
import io.airbyte.config.StandardSourceDefinition;
import io.airbyte.config.StandardSync;
import io.airbyte.config.StandardWorkspace;
import io.airbyte.config.SyncStats;
import io.airbyte.config.persistence.ActorDefinitionVersionHelper;
import io.airbyte.config.persistence.ConfigRepository;
import io.airbyte.metrics.lib.MetricAttribute;
import io.airbyte.metrics.lib.MetricClientFactory;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import io.airbyte.notification.CustomerioNotificationClient;
import io.airbyte.notification.NotificationClient;
import io.airbyte.notification.SlackNotificationClient;
import io.airbyte.notification.messages.ConnectionInfo;
import io.airbyte.notification.messages.DestinationInfo;
import io.airbyte.notification.messages.SourceInfo;
import io.airbyte.notification.messages.SyncSummary;
import io.airbyte.notification.messages.WorkspaceInfo;
import io.airbyte.persistence.job.models.Job;
import io.airbyte.persistence.job.models.JobStatus;
import io.airbyte.persistence.job.tracker.TrackingMetadata;
import io.micronaut.core.util.functional.ThrowingFunction;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.commons.lang3.time.DurationFormatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Send a notification to a user about something that happened to a Job.
 */
public class JobNotifier {

  private static final Logger LOGGER = LoggerFactory.getLogger(JobNotifier.class);

  public static final String FAILURE_NOTIFICATION = "Failure Notification";
  public static final String SUCCESS_NOTIFICATION = "Success Notification";
  public static final String CONNECTION_DISABLED_WARNING_NOTIFICATION = "Connection Disabled Warning Notification";
  public static final String CONNECTION_DISABLED_NOTIFICATION = "Connection Disabled Notification";

  private final ConfigRepository configRepository;
  private final TrackingClient trackingClient;
  private final WebUrlHelper webUrlHelper;
  private final WorkspaceHelper workspaceHelper;
  private final ActorDefinitionVersionHelper actorDefinitionVersionHelper;

  public JobNotifier(final WebUrlHelper webUrlHelper,
                     final ConfigRepository configRepository,
                     final WorkspaceHelper workspaceHelper,
                     final TrackingClient trackingClient,
                     final ActorDefinitionVersionHelper actorDefinitionVersionHelper) {
    this.webUrlHelper = webUrlHelper;
    this.workspaceHelper = workspaceHelper;
    this.configRepository = configRepository;
    this.trackingClient = trackingClient;
    this.actorDefinitionVersionHelper = actorDefinitionVersionHelper;
  }

  private void notifyJob(final String reason, final String action, final Job job, List<JobPersistence.AttemptStats> attemptStats) {
    try {
      final UUID workspaceId = workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(job.getId());
      final StandardWorkspace workspace = configRepository.getStandardWorkspaceNoSecrets(workspaceId, true);
      notifyJob(reason, action, job, attemptStats, workspace);
    } catch (final Exception e) {
      LOGGER.error("Unable to read configuration:", e);
    }
  }

  private void notifyJob(final String reason,
                         final String action,
                         final Job job,
                         final List<JobPersistence.AttemptStats> attempts,
                         final StandardWorkspace workspace) {
    final UUID connectionId = UUID.fromString(job.getScope());
    final NotificationSettings notificationSettings = workspace.getNotificationSettings();
    try {
      final StandardSync standardSync = configRepository.getStandardSync(connectionId);
      final StandardSourceDefinition sourceDefinition = configRepository.getSourceDefinitionFromConnection(connectionId);
      final StandardDestinationDefinition destinationDefinition = configRepository.getDestinationDefinitionFromConnection(connectionId);

      final SourceConnection source = configRepository.getSourceConnection(standardSync.getSourceId());
      final DestinationConnection destination = configRepository.getDestinationConnection(standardSync.getDestinationId());

      final ActorDefinitionVersion sourceVersion =
          actorDefinitionVersionHelper.getSourceVersion(sourceDefinition, workspace.getWorkspaceId(), standardSync.getSourceId());
      final ActorDefinitionVersion destinationVersion =
          actorDefinitionVersionHelper.getDestinationVersion(destinationDefinition, workspace.getWorkspaceId(), standardSync.getDestinationId());
      final Map<String, Object> jobMetadata = TrackingMetadata.generateJobAttemptMetadata(job);
      final Map<String, Object> sourceMetadata = TrackingMetadata.generateSourceDefinitionMetadata(sourceDefinition, sourceVersion);
      final Map<String, Object> destinationMetadata =
          TrackingMetadata.generateDestinationDefinitionMetadata(destinationDefinition, destinationVersion);

      final SyncStats syncStats = new SyncStats()
          .withBytesCommitted(0L).withBytesEmitted(0L)
          .withRecordsCommitted(0L).withRecordsEmitted(0L);
      for (var attemptStat : attempts) {
        SyncStats combinedStats = attemptStat.combinedStats();
        if (combinedStats != null) {
          syncStats.setBytesEmitted(syncStats.getBytesEmitted() + combinedStats.getBytesEmitted());
          syncStats.setBytesCommitted(syncStats.getBytesCommitted() + combinedStats.getBytesCommitted());
          syncStats.setRecordsEmitted(syncStats.getRecordsEmitted() + combinedStats.getRecordsEmitted());
          syncStats.setRecordsCommitted(syncStats.getRecordsCommitted() + combinedStats.getRecordsCommitted());
        }
      }
      final NotificationItem notificationItem = createAndSend(notificationSettings, action, connectionId,
          destinationDefinition, job, reason, sourceDefinition, standardSync, workspace, source, destination,
          syncStats);

      if (notificationItem != null) {
        final Map<String, Object> notificationMetadata = buildNotificationMetadata(connectionId, notificationItem);
        trackingClient.track(
            workspace.getWorkspaceId(),
            action,
            MoreMaps.merge(jobMetadata, sourceMetadata, destinationMetadata, notificationMetadata));
      }
    } catch (final Exception e) {
      LOGGER.error("Unable to read configuration for notification. Non-blocking. Error:", e);
    }
  }

  List<NotificationClient> getNotificationClientsFromNotificationItem(final NotificationItem item) {
    return item.getNotificationType().stream().map(notificationType -> {
      if (NotificationType.SLACK.equals(notificationType)) {
        return new SlackNotificationClient(item.getSlackConfiguration());
      } else if (NotificationType.CUSTOMERIO.equals(notificationType)) {
        return new CustomerioNotificationClient();
      } else {
        throw new IllegalArgumentException("Notification type not supported: " + notificationType);
      }
    }).collect(Collectors.toList());
  }

  Map<String, Object> buildNotificationMetadata(final UUID connectionId, final NotificationItem notificationItem) {
    final Builder<String, Object> notificationMetadata = ImmutableMap.builder();
    notificationMetadata.put("connection_id", connectionId);
    for (final var notificationType : notificationItem.getNotificationType()) {
      if (NotificationType.SLACK.equals(notificationType)
          && notificationItem.getSlackConfiguration().getWebhook().contains("hooks.slack.com")) {
        // flag as slack if the webhook URL is also pointing to slack
        notificationMetadata.put("notification_type", NotificationType.SLACK);
      } else if (NotificationType.CUSTOMERIO.equals(notificationType)) {
        notificationMetadata.put("notification_type", NotificationType.CUSTOMERIO);
      } else {
        // Slack Notification type could be "hacked" and re-used for custom webhooks
        notificationMetadata.put("notification_type", "N/A");
      }
    }
    return notificationMetadata.build();
  }

  private void submitToMetricClient(final String action, final String notificationClient) {
    final MetricAttribute metricTriggerAttribute = new MetricAttribute(NOTIFICATION_TRIGGER, action);
    final MetricAttribute metricClientAttribute = new MetricAttribute(NOTIFICATION_CLIENT, notificationClient);

    MetricClientFactory.getMetricClient().count(OssMetricsRegistry.NOTIFICATIONS_SENT, 1, metricClientAttribute,
        metricTriggerAttribute);
  }

  /**
   * This method allows for the alert to be sent without the customerio configuration set in the
   * database.
   * <p>
   * This is only needed because there is no UI element to allow for users to create that
   * configuration.
   * <p>
   * Once that exists, this can be removed and we should be using `notifyJobByEmail`. The alert is
   * sent to the email associated with the workspace.
   *
   * @param reason for notification
   * @param action tracking action for telemetry
   * @param job job notification is for
   * @param attemptStats sync stats for each attempts
   */
  public void notifyJobByEmail(final String reason, final String action, final Job job, List<JobPersistence.AttemptStats> attemptStats) {
    try {
      final UUID workspaceId = workspaceHelper.getWorkspaceForJobIdIgnoreExceptions(job.getId());
      final StandardWorkspace workspace = configRepository.getStandardWorkspaceNoSecrets(workspaceId, true);
      notifyJob(reason, action, job, attemptStats, workspace);
    } catch (final Exception e) {
      LOGGER.error("Unable to read configuration:", e);
    }
  }

  private String getJobDescription(final Job job, final String reason) {
    final Instant jobStartedDate = Instant.ofEpochSecond(job.getStartedAtInSecond().orElse(job.getCreatedAtInSecond()));
    final DateTimeFormatter formatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.FULL)
        .withZone(ZoneId.systemDefault());
    final Instant jobUpdatedDate = Instant.ofEpochSecond(job.getUpdatedAtInSecond());
    final Instant adjustedJobUpdatedDate = jobUpdatedDate.equals(jobStartedDate) ? Instant.now() : jobUpdatedDate;
    final Duration duration = Duration.between(jobStartedDate, adjustedJobUpdatedDate);
    final String durationString = DurationFormatUtils.formatDurationWords(duration.toMillis(),
        true, true);

    return String.format("sync started on %s, running for %s%s.",
        formatter.format(jobStartedDate), durationString, reason);
  }

  public void failJob(final String reason, final Job job, List<JobPersistence.AttemptStats> attemptStats) {
    notifyJob(reason, FAILURE_NOTIFICATION, job, attemptStats);
  }

  public void successJob(final Job job, List<JobPersistence.AttemptStats> attemptStats) {
    notifyJob(null, SUCCESS_NOTIFICATION, job, attemptStats);
  }

  public void autoDisableConnection(final Job job, List<JobPersistence.AttemptStats> attemptStats) {
    notifyJob(null, CONNECTION_DISABLED_NOTIFICATION, job, attemptStats);
  }

  public void autoDisableConnectionWarning(final Job job, List<JobPersistence.AttemptStats> attemptStats) {
    notifyJob(null, CONNECTION_DISABLED_WARNING_NOTIFICATION, job, attemptStats);
  }

  private void sendNotification(final NotificationItem notificationItem,
                                final String notificationTrigger,
                                final ThrowingFunction<NotificationClient, Boolean, Exception> executeNotification) {
    if (notificationItem == null) {
      // Note: we may be able to implement a log notifier to log notification message only.
      LOGGER.info("No notification item found for the desired notification event found. Skipping notification.");
      return;
    }
    final List<NotificationClient> notificationClients = getNotificationClientsFromNotificationItem(notificationItem);
    for (final NotificationClient notificationClient : notificationClients) {
      try {
        if (!executeNotification.apply(notificationClient)) {
          LOGGER.warn("Failed to successfully notify: {}", notificationItem);
        }
        submitToMetricClient(notificationTrigger, notificationClient.getNotificationClientType());
      } catch (final Exception ex) {
        LOGGER.error("Failed to notify: {} due to an exception. Not blocking.", notificationItem, ex);
        // Do not block.
      }
    }

  }

  private NotificationItem createAndSend(final NotificationSettings notificationSettings,
                                         final String action,
                                         final UUID connectionId,
                                         final StandardDestinationDefinition destinationDefinition,
                                         final Job job,
                                         final String reason,
                                         final StandardSourceDefinition sourceDefinition,
                                         final StandardSync standardSync,
                                         final StandardWorkspace workspace,
                                         final SourceConnection source,
                                         final DestinationConnection destination,
                                         final SyncStats syncStats) {
    NotificationItem notificationItem = null;
    final String sourceConnector = sourceDefinition.getName();
    final String destinationConnector = destinationDefinition.getName();
    final String failReason = Strings.isNullOrEmpty(reason) ? "" : String.format(", as the %s", reason);
    final String jobDescription = getJobDescription(job, failReason);
    final UUID workspaceId = workspace.getWorkspaceId();

    SyncSummary.SyncSummaryBuilder summaryBuilder = SyncSummary.builder()
        .workspace(WorkspaceInfo.builder()
            .name(workspace.getName()).id(workspaceId).url(webUrlHelper.getWorkspaceUrl(workspaceId)).build())
        .connection(ConnectionInfo.builder().name(standardSync.getName()).id(standardSync.getConnectionId())
            .url(webUrlHelper.getConnectionUrl(workspaceId, standardSync.getConnectionId())).build())
        .source(
            SourceInfo.builder()
                .name(source.getName()).id(source.getSourceId()).url(webUrlHelper.getSourceUrl(workspaceId, source.getSourceId())).build())
        .destination(DestinationInfo.builder()
            .name(destination.getName()).id(destination.getDestinationId())
            .url(webUrlHelper.getDestinationUrl(workspaceId, destination.getDestinationId())).build())
        .startedAt(Instant.ofEpochSecond(job.getCreatedAtInSecond()))
        .finishedAt(Instant.ofEpochSecond(job.getUpdatedAtInSecond()))
        .isSuccess(job.getStatus() == JobStatus.SUCCEEDED)
        .jobId(job.getId())
        .errorMessage(reason);

    if (syncStats != null) {
      summaryBuilder.bytesEmitted(syncStats.getBytesEmitted())
          .bytesCommitted(syncStats.getBytesEmitted())
          .recordsEmitted(syncStats.getRecordsEmitted())
          .recordsCommitted(syncStats.getRecordsCommitted());
    }

    SyncSummary summary = summaryBuilder.build();

    if (notificationSettings != null) {
      if (FAILURE_NOTIFICATION.equalsIgnoreCase(action)) {
        notificationItem = notificationSettings.getSendOnFailure();
        sendNotification(notificationItem, FAILURE_NOTIFICATION,
            (notificationClient) -> notificationClient.notifyJobFailure(summary, workspace.getEmail()));
      } else if (SUCCESS_NOTIFICATION.equalsIgnoreCase(action)) {
        notificationItem = notificationSettings.getSendOnSuccess();
        sendNotification(notificationItem, SUCCESS_NOTIFICATION,
            (notificationClient) -> notificationClient.notifyJobSuccess(summary, workspace.getEmail()));
      } else if (CONNECTION_DISABLED_NOTIFICATION.equalsIgnoreCase(action)) {
        notificationItem = notificationSettings.getSendOnSyncDisabled();
        sendNotification(notificationItem, CONNECTION_DISABLED_NOTIFICATION,
            (notificationClient) -> notificationClient.notifyConnectionDisabled(workspace.getEmail(),
                sourceConnector, destinationConnector, jobDescription, workspace.getWorkspaceId(), connectionId));
      } else if (CONNECTION_DISABLED_WARNING_NOTIFICATION.equalsIgnoreCase(action)) {
        notificationItem = notificationSettings.getSendOnSyncDisabledWarning();
        sendNotification(notificationItem, CONNECTION_DISABLED_WARNING_NOTIFICATION,
            (notificationClient) -> notificationClient.notifyConnectionDisableWarning(workspace.getEmail(),
                sourceConnector, destinationConnector, jobDescription, workspace.getWorkspaceId(), connectionId));
      }
    } else {
      LOGGER.warn("Unable to send notification:  notification settings are not present.");
    }

    return notificationItem;
  }

}

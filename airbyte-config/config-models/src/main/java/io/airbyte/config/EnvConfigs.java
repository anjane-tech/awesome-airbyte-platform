/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.config;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import io.airbyte.commons.constants.AirbyteCatalogConstants;
import io.airbyte.commons.lang.Exceptions;
import io.airbyte.commons.map.MoreMaps;
import io.airbyte.commons.version.AirbyteVersion;
import io.airbyte.config.helpers.LogClientSingleton;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.config.storage.CloudStorageConfigs;
import io.airbyte.config.storage.CloudStorageConfigs.GcsConfig;
import io.airbyte.config.storage.CloudStorageConfigs.MinioConfig;
import io.airbyte.config.storage.CloudStorageConfigs.S3Config;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Configs from environment variables.
 */
@SuppressWarnings({"PMD.LongVariable", "PMD.CyclomaticComplexity", "PMD.AvoidReassigningParameters", "PMD.ConstructorCallsOverridableMethod"})
public class EnvConfigs implements Configs {

  private static final Logger LOGGER = LoggerFactory.getLogger(EnvConfigs.class);

  // env variable names
  public static final String AIRBYTE_ROLE = "AIRBYTE_ROLE";
  public static final String AIRBYTE_VERSION = "AIRBYTE_VERSION";
  public static final String INTERNAL_API_HOST = "INTERNAL_API_HOST";
  public static final String AIRBYTE_API_AUTH_HEADER_VALUE = "AIRBYTE_API_AUTH_HEADER_VALUE";
  public static final String WORKER_ENVIRONMENT = "WORKER_ENVIRONMENT";
  public static final String LOCAL_CONNECTOR_CATALOG_PATH = "LOCAL_CONNECTOR_CATALOG_PATH";
  public static final String WORKSPACE_ROOT = "WORKSPACE_ROOT";
  public static final String WORKSPACE_DOCKER_MOUNT = "WORKSPACE_DOCKER_MOUNT";
  public static final String LOCAL_ROOT = "LOCAL_ROOT";
  public static final String LOCAL_DOCKER_MOUNT = "LOCAL_DOCKER_MOUNT";
  public static final String LOG4J_CONFIGURATION_FILE = "LOG4J_CONFIGURATION_FILE";
  public static final String CONFIG_ROOT = "CONFIG_ROOT";
  public static final String DOCKER_NETWORK = "DOCKER_NETWORK";
  public static final String JOB_ERROR_REPORTING_STRATEGY = "JOB_ERROR_REPORTING_STRATEGY";
  public static final String JOB_ERROR_REPORTING_SENTRY_DSN = "JOB_ERROR_REPORTING_SENTRY_DSN";
  public static final String DEPLOYMENT_MODE = "DEPLOYMENT_MODE";
  public static final String DATABASE_USER = "DATABASE_USER";
  public static final String DATABASE_PASSWORD = "DATABASE_PASSWORD";
  public static final String DATABASE_URL = "DATABASE_URL";
  public static final String JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_POLICY = "JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_POLICY";
  public static final String JOB_KUBE_SIDECAR_CONTAINER_IMAGE_PULL_POLICY = "JOB_KUBE_SIDECAR_CONTAINER_IMAGE_PULL_POLICY";
  public static final String JOB_KUBE_SERVICEACCOUNT = "JOB_KUBE_SERVICEACCOUNT";
  public static final String JOB_KUBE_TOLERATIONS = "JOB_KUBE_TOLERATIONS";
  public static final String JOB_KUBE_NODE_SELECTORS = "JOB_KUBE_NODE_SELECTORS";
  public static final String JOB_ISOLATED_KUBE_NODE_SELECTORS = "JOB_ISOLATED_KUBE_NODE_SELECTORS";
  public static final String USE_CUSTOM_NODE_SELECTOR = "USE_CUSTOM_NODE_SELECTOR";
  public static final String JOB_KUBE_ANNOTATIONS = "JOB_KUBE_ANNOTATIONS";
  public static final String JOB_KUBE_LABELS = "JOB_KUBE_LABELS";
  private static final String DEFAULT_SIDECAR_MEMORY_REQUEST = "25Mi";
  private static final String SIDECAR_MEMORY_REQUEST = "SIDECAR_MEMORY_REQUEST";
  private static final String DEFAULT_SIDECAR_KUBE_MEMORY_LIMIT = "50Mi";
  private static final String SIDECAR_KUBE_MEMORY_LIMIT = "SIDECAR_KUBE_MEMORY_LIMIT";
  private static final String DEFAULT_SIDECAR_KUBE_CPU_REQUEST = "0.1";
  private static final String SIDECAR_KUBE_CPU_REQUEST = "SIDECAR_KUBE_CPU_REQUEST";

  private static final String CUSTOMERIO_API_KEY = "CUSTOMERIO_API_KEY";
  // Test show at least 1.5 CPU is required to hit >20 Mb/s. Overprovision to ensure sidecar resources
  // do not cause bottlenecks.
  // This is fine as the limit only affects whether the container is throttled by Kube. It does not
  // affect scheduling.
  private static final String DEFAULT_SIDECAR_KUBE_CPU_LIMIT = "2.0";
  private static final String SIDECAR_KUBE_CPU_LIMIT = "SIDECAR_KUBE_CPU_LIMIT";
  public static final String JOB_KUBE_SOCAT_IMAGE = "JOB_KUBE_SOCAT_IMAGE";
  public static final String SOCAT_KUBE_CPU_LIMIT = "SOCAT_KUBE_CPU_LIMIT";
  public static final String SOCAT_KUBE_CPU_REQUEST = "SOCAT_KUBE_CPU_REQUEST";
  public static final String JOB_KUBE_BUSYBOX_IMAGE = "JOB_KUBE_BUSYBOX_IMAGE";
  public static final String JOB_KUBE_CURL_IMAGE = "JOB_KUBE_CURL_IMAGE";
  public static final String SYNC_JOB_INIT_RETRY_TIMEOUT_MINUTES = "SYNC_JOB_INIT_RETRY_TIMEOUT_MINUTES";
  private static final String CONNECTOR_SPECIFIC_RESOURCE_DEFAULTS_ENABLED = "CONNECTOR_SPECIFIC_RESOURCE_DEFAULTS_ENABLED";
  private static final String TEMPORAL_HISTORY_RETENTION_IN_DAYS = "TEMPORAL_HISTORY_RETENTION_IN_DAYS";
  public static final String JOB_KUBE_NAMESPACE = "JOB_KUBE_NAMESPACE";
  public static final String JOB_MAIN_CONTAINER_CPU_REQUEST = "JOB_MAIN_CONTAINER_CPU_REQUEST";
  public static final String JOB_MAIN_CONTAINER_CPU_LIMIT = "JOB_MAIN_CONTAINER_CPU_LIMIT";
  public static final String JOB_MAIN_CONTAINER_MEMORY_REQUEST = "JOB_MAIN_CONTAINER_MEMORY_REQUEST";
  public static final String JOB_MAIN_CONTAINER_MEMORY_LIMIT = "JOB_MAIN_CONTAINER_MEMORY_LIMIT";
  public static final String JOB_DEFAULT_ENV_MAP = "JOB_DEFAULT_ENV_MAP";
  public static final String JOB_DEFAULT_ENV_PREFIX = "JOB_DEFAULT_ENV_";
  private static final String SECRET_PERSISTENCE = "SECRET_PERSISTENCE";
  public static final String JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_SECRET = "JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_SECRET";
  public static final String PUBLISH_METRICS = "PUBLISH_METRICS";
  public static final String DD_AGENT_HOST = "DD_AGENT_HOST";
  public static final String DD_DOGSTATSD_PORT = "DD_DOGSTATSD_PORT";
  public static final String DD_CONSTANT_TAGS = "DD_CONSTANT_TAGS";
  public static final String STATE_STORAGE_S3_BUCKET_NAME = "STATE_STORAGE_S3_BUCKET_NAME";
  public static final String STATE_STORAGE_S3_REGION = "STATE_STORAGE_S3_REGION";
  public static final String STATE_STORAGE_S3_ACCESS_KEY = "STATE_STORAGE_S3_ACCESS_KEY";
  public static final String STATE_STORAGE_S3_SECRET_ACCESS_KEY = "STATE_STORAGE_S3_SECRET_ACCESS_KEY";
  public static final String STATE_STORAGE_MINIO_BUCKET_NAME = "STATE_STORAGE_MINIO_BUCKET_NAME";
  public static final String STATE_STORAGE_MINIO_ENDPOINT = "STATE_STORAGE_MINIO_ENDPOINT";
  public static final String STATE_STORAGE_MINIO_ACCESS_KEY = "STATE_STORAGE_MINIO_ACCESS_KEY";
  public static final String STATE_STORAGE_MINIO_SECRET_ACCESS_KEY = "STATE_STORAGE_MINIO_SECRET_ACCESS_KEY";
  public static final String STATE_STORAGE_GCS_BUCKET_NAME = "STATE_STORAGE_GCS_BUCKET_NAME";
  public static final String STATE_STORAGE_GCS_APPLICATION_CREDENTIALS = "STATE_STORAGE_GCS_APPLICATION_CREDENTIALS";

  public static final String METRIC_CLIENT = "METRIC_CLIENT";
  public static final String OTEL_COLLECTOR_ENDPOINT = "OTEL_COLLECTOR_ENDPOINT";

  // job-type-specific overrides
  public static final String SPEC_JOB_KUBE_NODE_SELECTORS = "SPEC_JOB_KUBE_NODE_SELECTORS";
  public static final String CHECK_JOB_KUBE_NODE_SELECTORS = "CHECK_JOB_KUBE_NODE_SELECTORS";
  public static final String DISCOVER_JOB_KUBE_NODE_SELECTORS = "DISCOVER_JOB_KUBE_NODE_SELECTORS";

  public static final String WORKER_LOGS_STORAGE_TYPE = "WORKER_LOGS_STORAGE_TYPE";
  public static final String WORKER_STATE_STORAGE_TYPE = "WORKER_STATE_STORAGE_TYPE";

  private static final String DEFAULT_JOB_KUBE_NAMESPACE = "default";
  private static final String DEFAULT_JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_POLICY = "IfNotPresent";
  private static final String DEFAULT_JOB_KUBE_SIDECAR_CONTAINER_IMAGE_PULL_POLICY = "IfNotPresent";
  private static final String DEFAULT_JOB_KUBE_SOCAT_IMAGE = "alpine/socat:1.7.4.4-r0";
  private static final String DEFAULT_JOB_KUBE_BUSYBOX_IMAGE = "busybox:1.35";
  private static final String DEFAULT_JOB_KUBE_CURL_IMAGE = "curlimages/curl:7.87.0";
  private static final String DEFAULT_NETWORK = "host";

  public static final Map<String, Function<EnvConfigs, String>> JOB_SHARED_ENVS = Map.of(
      AIRBYTE_VERSION, (instance) -> instance.getAirbyteVersion().serialize(),
      AIRBYTE_ROLE, EnvConfigs::getAirbyteRole,
      DEPLOYMENT_MODE, (instance) -> instance.getDeploymentMode().name(),
      WORKER_ENVIRONMENT, (instance) -> instance.getWorkerEnvironment().name());

  public static final int DEFAULT_TEMPORAL_HISTORY_RETENTION_IN_DAYS = 30;

  public static final int DEFAULT_FAILED_JOBS_IN_A_ROW_BEFORE_CONNECTION_DISABLE = 100;
  public static final int DEFAULT_DAYS_OF_ONLY_FAILED_JOBS_BEFORE_CONNECTION_DISABLE = 14;
  public static final String LAUNCHDARKLY_KEY = "LAUNCHDARKLY_KEY";

  public static final String FEATURE_FLAG_CLIENT = "FEATURE_FLAG_CLIENT";

  private final Function<String, String> getEnv;
  private final Supplier<Set<String>> getAllEnvKeys;
  private final LogConfigs logConfigs;
  private final CloudStorageConfigs stateStorageCloudConfigs;

  public static final String CDK_PYTHON = "CDK_PYTHON";
  public static final String CDK_ENTRYPOINT = "CDK_ENTRYPOINT";

  /**
   * Constructs {@link EnvConfigs} from actual environment variables.
   */
  public EnvConfigs() {
    this(System.getenv());
  }

  /**
   * Constructs {@link EnvConfigs} from a provided map. This can be used for testing or getting
   * variables from a non-envvar source.
   */
  public EnvConfigs(final Map<String, String> envMap) {
    this.getEnv = envMap::get;
    this.getAllEnvKeys = envMap::keySet;
    this.logConfigs = new LogConfigs(getLogConfiguration());
    this.stateStorageCloudConfigs = getStateStorageConfiguration().orElse(null);
  }

  private Optional<CloudStorageConfigs> getLogConfiguration() {
    if (getEnv(LogClientSingleton.GCS_LOG_BUCKET) != null && !getEnv(LogClientSingleton.GCS_LOG_BUCKET).isBlank()) {
      return Optional.of(CloudStorageConfigs.gcs(new GcsConfig(
          getEnvOrDefault(LogClientSingleton.GCS_LOG_BUCKET, ""),
          getEnvOrDefault(LogClientSingleton.GOOGLE_APPLICATION_CREDENTIALS, ""))));
    } else if (getEnv(LogClientSingleton.S3_MINIO_ENDPOINT) != null && !getEnv(LogClientSingleton.S3_MINIO_ENDPOINT).isBlank()) {
      return Optional.of(CloudStorageConfigs.minio(new MinioConfig(
          getEnvOrDefault(LogClientSingleton.S3_LOG_BUCKET, ""),
          getEnvOrDefault(LogClientSingleton.AWS_ACCESS_KEY_ID, ""),
          getEnvOrDefault(LogClientSingleton.AWS_SECRET_ACCESS_KEY, ""),
          getEnvOrDefault(LogClientSingleton.S3_MINIO_ENDPOINT, ""))));
    } else if (getEnv(LogClientSingleton.S3_LOG_BUCKET_REGION) != null && !getEnv(LogClientSingleton.S3_LOG_BUCKET_REGION).isBlank()) {
      return Optional.of(CloudStorageConfigs.s3(new S3Config(
          getEnvOrDefault(LogClientSingleton.S3_LOG_BUCKET, ""),
          getEnvOrDefault(LogClientSingleton.AWS_ACCESS_KEY_ID, ""),
          getEnvOrDefault(LogClientSingleton.AWS_SECRET_ACCESS_KEY, ""),
          getEnvOrDefault(LogClientSingleton.S3_LOG_BUCKET_REGION, ""))));
    } else {
      return Optional.empty();
    }
  }

  private Optional<CloudStorageConfigs> getStateStorageConfiguration() {
    if (getEnv(STATE_STORAGE_GCS_BUCKET_NAME) != null && !getEnv(STATE_STORAGE_GCS_BUCKET_NAME).isBlank()) {
      return Optional.of(CloudStorageConfigs.gcs(new GcsConfig(
          getEnvOrDefault(STATE_STORAGE_GCS_BUCKET_NAME, ""),
          getEnvOrDefault(STATE_STORAGE_GCS_APPLICATION_CREDENTIALS, ""))));
    } else if (getEnv(STATE_STORAGE_MINIO_ENDPOINT) != null && !getEnv(STATE_STORAGE_MINIO_ENDPOINT).isBlank()) {
      return Optional.of(CloudStorageConfigs.minio(new MinioConfig(
          getEnvOrDefault(STATE_STORAGE_MINIO_BUCKET_NAME, ""),
          getEnvOrDefault(STATE_STORAGE_MINIO_ACCESS_KEY, ""),
          getEnvOrDefault(STATE_STORAGE_MINIO_SECRET_ACCESS_KEY, ""),
          getEnvOrDefault(STATE_STORAGE_MINIO_ENDPOINT, ""))));
    } else if (getEnv(STATE_STORAGE_S3_REGION) != null && !getEnv(STATE_STORAGE_S3_REGION).isBlank()) {
      return Optional.of(CloudStorageConfigs.s3(new S3Config(
          getEnvOrDefault(STATE_STORAGE_S3_BUCKET_NAME, ""),
          getEnvOrDefault(STATE_STORAGE_S3_ACCESS_KEY, ""),
          getEnvOrDefault(STATE_STORAGE_S3_SECRET_ACCESS_KEY, ""),
          getEnvOrDefault(STATE_STORAGE_S3_REGION, ""))));
    } else {
      return Optional.empty();
    }
  }

  // CORE
  // General
  @Override
  public String getAirbyteRole() {
    return getEnv(AIRBYTE_ROLE);
  }

  @Override
  public AirbyteVersion getAirbyteVersion() {
    return new AirbyteVersion(getEnsureEnv(AIRBYTE_VERSION));
  }

  @Override
  public String getAirbyteVersionOrWarning() {
    return Optional.ofNullable(getEnv(AIRBYTE_VERSION)).orElse("version not set");
  }

  public String getLocalCatalogPath() {
    return getEnvOrDefault(LOCAL_CONNECTOR_CATALOG_PATH, AirbyteCatalogConstants.DEFAULT_LOCAL_CONNECTOR_CATALOG_PATH);
  }

  @Override
  public DeploymentMode getDeploymentMode() {
    return getEnvOrDefault(DEPLOYMENT_MODE, DeploymentMode.OSS, s -> {
      try {
        return DeploymentMode.valueOf(s);
      } catch (final IllegalArgumentException e) {
        LOGGER.info(s + " not recognized, defaulting to " + DeploymentMode.OSS);
        return DeploymentMode.OSS;
      }
    });
  }

  @Override
  public WorkerEnvironment getWorkerEnvironment() {
    return getEnvOrDefault(WORKER_ENVIRONMENT, WorkerEnvironment.DOCKER, s -> WorkerEnvironment.valueOf(s.toUpperCase()));
  }

  @Override
  public Path getConfigRoot() {
    return getPath(CONFIG_ROOT);
  }

  @Override
  public Path getWorkspaceRoot() {
    return getPath(WORKSPACE_ROOT);
  }

  // Docker Only
  @Override
  public String getWorkspaceDockerMount() {
    return getEnvOrDefault(WORKSPACE_DOCKER_MOUNT, getWorkspaceRoot().toString());
  }

  @Override
  public String getLocalDockerMount() {
    return getEnvOrDefault(LOCAL_DOCKER_MOUNT, getLocalRoot().toString());
  }

  @Override
  public String getDockerNetwork() {
    return getEnvOrDefault(DOCKER_NETWORK, DEFAULT_NETWORK);
  }

  @Override
  public Path getLocalRoot() {
    return getPath(LOCAL_ROOT);
  }

  // Secrets

  @Override
  public SecretPersistenceType getSecretPersistenceType() {
    final var secretPersistenceStr = getEnvOrDefault(SECRET_PERSISTENCE, SecretPersistenceType.TESTING_CONFIG_DB_TABLE.name());
    return SecretPersistenceType.valueOf(secretPersistenceStr);
  }

  // Database
  @Override
  public String getDatabaseUser() {
    return getEnsureEnv(DATABASE_USER);
  }

  @Override
  public String getDatabasePassword() {
    return getEnsureEnv(DATABASE_PASSWORD);
  }

  @Override
  public String getDatabaseUrl() {
    return getEnsureEnv(DATABASE_URL);
  }

  @Override
  public int getTemporalRetentionInDays() {
    return getEnvOrDefault(TEMPORAL_HISTORY_RETENTION_IN_DAYS, DEFAULT_TEMPORAL_HISTORY_RETENTION_IN_DAYS);
  }

  @Override
  public int getJobInitRetryTimeoutMinutes() {
    return Integer.parseInt(getEnvOrDefault(SYNC_JOB_INIT_RETRY_TIMEOUT_MINUTES, "5"));
  }

  @Override
  public boolean connectorSpecificResourceDefaultsEnabled() {
    return getEnvOrDefault(CONNECTOR_SPECIFIC_RESOURCE_DEFAULTS_ENABLED, false);
  }

  /**
   * Returns worker pod tolerations parsed from its own environment variable. The value of the env is
   * a string that represents one or more tolerations.
   * <ul>
   * <li>Tolerations are separated by a `;`
   * <li>Each toleration contains k=v pairs mentioning some/all of key, effect, operator and value and
   * separated by `,`
   * </ul>
   * <p>
   * For example:- The following represents two tolerations, one checking existence and another
   * matching a value
   * <p>
   * key=airbyte-server,operator=Exists,effect=NoSchedule;key=airbyte-server,operator=Equals,value=true,effect=NoSchedule
   *
   * @return list of WorkerKubeToleration parsed from env
   */
  @Override
  public List<TolerationPOJO> getJobKubeTolerations() {
    final String tolerationsStr = getEnvOrDefault(JOB_KUBE_TOLERATIONS, "");

    final Stream<String> tolerations = Strings.isNullOrEmpty(tolerationsStr) ? Stream.of()
        : Splitter.on(";")
            .splitToStream(tolerationsStr)
            .filter(tolerationStr -> !Strings.isNullOrEmpty(tolerationStr));

    return tolerations
        .map(this::parseToleration)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());
  }

  private TolerationPOJO parseToleration(final String tolerationStr) {
    final Map<String, String> tolerationMap = Splitter.on(",")
        .splitToStream(tolerationStr)
        .map(s -> s.split("="))
        .collect(Collectors.toMap(s -> s[0], s -> s[1]));

    if (tolerationMap.containsKey("key") && tolerationMap.containsKey("effect") && tolerationMap.containsKey("operator")) {
      return new TolerationPOJO(
          tolerationMap.get("key"),
          tolerationMap.get("effect"),
          tolerationMap.get("value"),
          tolerationMap.get("operator"));
    } else {
      LOGGER.warn(
          "Ignoring toleration {}, missing one of key,effect or operator",
          tolerationStr);
      return null;
    }
  }

  /**
   * Returns a map of node selectors for any job type. Used as a default if a particular job type does
   * not define its own node selector environment variable.
   *
   * @return map containing kv pairs of node selectors, or empty optional if none present.
   */
  @Override
  public Map<String, String> getJobKubeNodeSelectors() {
    return splitKVPairsFromEnvString(getEnvOrDefault(JOB_KUBE_NODE_SELECTORS, ""));
  }

  @Override
  public Map<String, String> getIsolatedJobKubeNodeSelectors() {
    return splitKVPairsFromEnvString(getEnvOrDefault(JOB_ISOLATED_KUBE_NODE_SELECTORS, ""));
  }

  @Override
  public boolean getUseCustomKubeNodeSelector() {
    return getEnvOrDefault(USE_CUSTOM_NODE_SELECTOR, false);
  }

  /**
   * Returns a map of node selectors for Spec job pods specifically.
   *
   * @return map containing kv pairs of node selectors, or empty optional if none present.
   */
  @Override
  public Map<String, String> getSpecJobKubeNodeSelectors() {
    return splitKVPairsFromEnvString(getEnvOrDefault(SPEC_JOB_KUBE_NODE_SELECTORS, ""));
  }

  /**
   * Returns a map of node selectors for Check job pods specifically.
   *
   * @return map containing kv pairs of node selectors, or empty optional if none present.
   */
  @Override
  public Map<String, String> getCheckJobKubeNodeSelectors() {
    return splitKVPairsFromEnvString(getEnvOrDefault(CHECK_JOB_KUBE_NODE_SELECTORS, ""));
  }

  /**
   * Returns a map of node selectors for Discover job pods specifically.
   *
   * @return map containing kv pairs of node selectors, or empty optional if none present.
   */
  @Override
  public Map<String, String> getDiscoverJobKubeNodeSelectors() {
    return splitKVPairsFromEnvString(getEnvOrDefault(DISCOVER_JOB_KUBE_NODE_SELECTORS, ""));
  }

  /**
   * Returns a map of annotations from its own environment variable. The value of the env is a string
   * that represents one or more annotations. Each kv-pair is separated by a `,`
   * <p>
   * For example:- The following represents two annotations
   * <p>
   * airbyte=server,type=preemptive
   *
   * @return map containing kv pairs of annotations
   */
  @Override
  public Map<String, String> getJobKubeAnnotations() {
    return splitKVPairsFromEnvString(getEnvOrDefault(JOB_KUBE_ANNOTATIONS, ""));
  }

  @Override
  public Map<String, String> getJobKubeLabels() {
    return splitKVPairsFromEnvString(getEnvOrDefault(JOB_KUBE_LABELS, ""));
  }

  /**
   * Splits key value pairs from the input string into a map. Each kv-pair is separated by a ','. The
   * key and the value are separated by '='.
   * <p>
   * For example:- The following represents two map entries
   * </p>
   * key1=value1,key2=value2
   *
   * @param input string
   * @return map containing kv pairs
   */
  public Map<String, String> splitKVPairsFromEnvString(String input) {
    if (input == null) {
      input = "";
    }
    final Map<String, String> map = Splitter.on(",")
        .splitToStream(input)
        .filter(s -> !Strings.isNullOrEmpty(s) && s.contains("="))
        .map(s -> s.split("="))
        .collect(Collectors.toMap(s -> s[0].trim(), s -> s[1].trim()));
    return map.isEmpty() ? null : map;
  }

  @Override
  public String getJobKubeMainContainerImagePullPolicy() {
    return getEnvOrDefault(JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_POLICY, DEFAULT_JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_POLICY);
  }

  @Override
  public String getJobKubeSidecarContainerImagePullPolicy() {
    return getEnvOrDefault(JOB_KUBE_SIDECAR_CONTAINER_IMAGE_PULL_POLICY, DEFAULT_JOB_KUBE_SIDECAR_CONTAINER_IMAGE_PULL_POLICY);
  }

  /**
   * Returns the name of the secret to be used when pulling down docker images for jobs. Automatically
   * injected in the KubePodProcess class and used in the job pod templates.
   * <p>
   * Can provide multiple strings seperated by comma(,) to indicate pulling from different
   * repositories. The empty string is a no-op value.
   */
  @Override
  public List<String> getJobKubeMainContainerImagePullSecrets() {
    final String secrets = getEnvOrDefault(JOB_KUBE_MAIN_CONTAINER_IMAGE_PULL_SECRET, "");
    return Arrays.stream(secrets.split(",")).collect(Collectors.toList());
  }

  @Override
  public String getSidecarKubeCpuRequest() {
    return getEnvOrDefault(SIDECAR_KUBE_CPU_REQUEST, DEFAULT_SIDECAR_KUBE_CPU_REQUEST);
  }

  @Override
  public String getSidecarKubeCpuLimit() {
    return getEnvOrDefault(SIDECAR_KUBE_CPU_LIMIT, DEFAULT_SIDECAR_KUBE_CPU_LIMIT);
  }

  @Override
  public String getSidecarKubeMemoryLimit() {
    return getEnvOrDefault(SIDECAR_KUBE_MEMORY_LIMIT, DEFAULT_SIDECAR_KUBE_MEMORY_LIMIT);
  }

  @Override
  public String getSidecarMemoryRequest() {
    return getEnvOrDefault(SIDECAR_MEMORY_REQUEST, DEFAULT_SIDECAR_MEMORY_REQUEST);
  }

  @Override
  public String getJobKubeSocatImage() {
    return getEnvOrDefault(JOB_KUBE_SOCAT_IMAGE, DEFAULT_JOB_KUBE_SOCAT_IMAGE);
  }

  @Override
  public String getSocatSidecarKubeCpuRequest() {
    return getEnvOrDefault(SOCAT_KUBE_CPU_REQUEST, getSidecarKubeCpuRequest());
  }

  @Override
  public String getSocatSidecarKubeCpuLimit() {
    return getEnvOrDefault(SOCAT_KUBE_CPU_LIMIT, getSidecarKubeCpuLimit());
  }

  @Override
  public String getJobKubeBusyboxImage() {
    return getEnvOrDefault(JOB_KUBE_BUSYBOX_IMAGE, DEFAULT_JOB_KUBE_BUSYBOX_IMAGE);
  }

  @Override
  public String getJobKubeCurlImage() {
    return getEnvOrDefault(JOB_KUBE_CURL_IMAGE, DEFAULT_JOB_KUBE_CURL_IMAGE);
  }

  @Override
  public String getJobKubeNamespace() {
    return getEnvOrDefault(JOB_KUBE_NAMESPACE, DEFAULT_JOB_KUBE_NAMESPACE);
  }

  @Override
  public String getMetricClient() {
    return getEnvOrDefault(METRIC_CLIENT, "");
  }

  @Override
  public String getOtelCollectorEndpoint() {
    return getEnvOrDefault(OTEL_COLLECTOR_ENDPOINT, "");
  }

  @Override
  public String getLaunchDarklyKey() {
    return getEnvOrDefault(LAUNCHDARKLY_KEY, "");
  }

  @Override
  public String getFeatureFlagClient() {
    return getEnvOrDefault(FEATURE_FLAG_CLIENT, "");
  }

  /**
   * There are two types of environment variables available to the job container.
   * <ul>
   * <li>Exclusive variables prefixed with JOB_DEFAULT_ENV_PREFIX</li>
   * <li>Shared variables defined in JOB_SHARED_ENVS</li>
   * </ul>
   */
  @Override
  public Map<String, String> getJobDefaultEnvMap() {
    final Map<String, String> jobPrefixedEnvMap = getAllEnvKeys.get().stream()
        .filter(key -> key.startsWith(JOB_DEFAULT_ENV_PREFIX))
        .collect(Collectors.toMap(key -> key.replace(JOB_DEFAULT_ENV_PREFIX, ""), getEnv));
    // This method assumes that these shared env variables are not critical to the execution
    // of the jobs, and only serve as metadata. So any exception is swallowed and default to
    // an empty string. Change this logic if this assumption no longer holds.
    final Map<String, String> jobSharedEnvMap = JOB_SHARED_ENVS.entrySet().stream().collect(Collectors.toMap(
        Entry::getKey,
        entry -> Exceptions.swallowWithDefault(() -> Objects.requireNonNullElse(entry.getValue().apply(this), ""), "")));
    return MoreMaps.merge(jobPrefixedEnvMap, jobSharedEnvMap);
  }

  @Override
  public LogConfigs getLogConfigs() {
    return logConfigs;
  }

  @Override
  public String getGoogleApplicationCredentials() {
    return getEnvOrDefault(LogClientSingleton.GOOGLE_APPLICATION_CREDENTIALS, null);
  }

  @Override
  public CloudStorageConfigs getStateStorageCloudConfigs() {
    return stateStorageCloudConfigs;
  }

  @Override
  public boolean getPublishMetrics() {
    return getEnvOrDefault(PUBLISH_METRICS, false);
  }

  @Override
  public String getDDAgentHost() {
    return getEnvOrDefault(DD_AGENT_HOST, "");
  }

  @Override
  public String getDDDogStatsDPort() {
    return getEnvOrDefault(DD_DOGSTATSD_PORT, "");
  }

  @Override
  public List<String> getDDConstantTags() {
    final String tagsString = getEnvOrDefault(DD_CONSTANT_TAGS, "");
    return Splitter.on(",")
        .splitToStream(tagsString)
        .filter(s -> !s.trim().isBlank())
        .collect(Collectors.toList());
  }

  @Override
  public JobErrorReportingStrategy getJobErrorReportingStrategy() {
    return getEnvOrDefault(JOB_ERROR_REPORTING_STRATEGY, JobErrorReportingStrategy.LOGGING, s -> {
      try {
        return JobErrorReportingStrategy.valueOf(s.toUpperCase());
      } catch (final IllegalArgumentException e) {
        LOGGER.info(s + " not recognized, defaulting to " + JobErrorReportingStrategy.LOGGING);
        return JobErrorReportingStrategy.LOGGING;
      }
    });
  }

  @Override
  public String getJobErrorReportingSentryDSN() {
    return getEnvOrDefault(JOB_ERROR_REPORTING_SENTRY_DSN, "");
  }

  // APPLICATIONS
  // Worker

  @Override
  public String getCdkPython() {
    return getEnv(CDK_PYTHON);
  }

  @Override
  public String getCdkEntrypoint() {
    return getEnv(CDK_ENTRYPOINT);
  }

  @Override
  public String getCustomerIoKey() {
    return getEnvOrDefault(CUSTOMERIO_API_KEY, null);
  }

  // Helpers
  public String getEnvOrDefault(final String key, final String defaultValue) {
    return getEnvOrDefault(key, defaultValue, Function.identity(), false);
  }

  public int getEnvOrDefault(final String key, final int defaultValue) {
    return getEnvOrDefault(key, defaultValue, Integer::parseInt, false);
  }

  public boolean getEnvOrDefault(final String key, final boolean defaultValue) {
    return getEnvOrDefault(key, defaultValue, Boolean::parseBoolean);
  }

  public <T> T getEnvOrDefault(final String key, final T defaultValue, final Function<String, T> parser) {
    return getEnvOrDefault(key, defaultValue, parser, false);
  }

  /**
   * Get env variable or default value.
   *
   * @param key of env variable
   * @param defaultValue to use if env variable is not present
   * @param parser function to parse env variable to desired type
   * @param isSecret is the env variable a secret
   * @param <T> type of env env variable
   * @return env variable
   */
  public <T> T getEnvOrDefault(final String key, final T defaultValue, final Function<String, T> parser, final boolean isSecret) {
    final String value = getEnv.apply(key);
    if (value != null && !value.isEmpty()) {
      return parser.apply(value);
    } else {
      LOGGER.info("Using default value for environment variable {}: '{}'", key, isSecret ? "*****" : defaultValue);
      return defaultValue;
    }
  }

  /**
   * Get env variable as string.
   *
   * @param name of env variable
   * @return value of env variable
   */
  public String getEnv(final String name) {
    return getEnv.apply(name);
  }

  /**
   * Get env variable or throw if null.
   *
   * @param name of env variable
   * @return value of env variable
   */
  public String getEnsureEnv(final String name) {
    final String value = getEnv(name);
    Preconditions.checkArgument(value != null, "'%s' environment variable cannot be null", name);

    return value;
  }

  private Path getPath(final String name) {
    final String value = getEnv.apply(name);
    if (value == null) {
      throw new IllegalArgumentException("Env variable not defined: " + name);
    }
    return Path.of(value);
  }

}

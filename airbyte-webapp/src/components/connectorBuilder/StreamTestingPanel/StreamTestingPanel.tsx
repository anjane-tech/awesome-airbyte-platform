import React, { useMemo } from "react";
import { FormattedMessage, useIntl } from "react-intl";
import { ValidationError } from "yup";

import { Heading } from "components/ui/Heading";
import { Spinner } from "components/ui/Spinner";

import { ConnectorBuilderProjectTestingValues } from "core/api/types/AirbyteClient";
import { Spec } from "core/api/types/ConnectorManifest";
import { jsonSchemaToFormBlock } from "core/form/schemaToFormBlock";
import { buildYupFormForJsonSchema } from "core/form/schemaToYup";
import { useAirbyteTheme } from "hooks/theme/useAirbyteTheme";
import {
  useConnectorBuilderFormState,
  useConnectorBuilderFormManagementState,
} from "services/connectorBuilder/ConnectorBuilderStateService";

import addStreamScreenshotDark from "./add-stream-screenshot-dark.png";
import addStreamScreenshotLight from "./add-stream-screenshot-light.png";
import { StreamSelector } from "./StreamSelector";
import { StreamTester } from "./StreamTester";
import styles from "./StreamTestingPanel.module.scss";
import { TestingValuesMenu } from "./TestingValuesMenu";
import { useBuilderWatch } from "../types";

const EMPTY_SCHEMA = {};

function useTestingValuesErrors(testingValues: ConnectorBuilderProjectTestingValues | undefined, spec?: Spec): number {
  const { formatMessage } = useIntl();

  return useMemo(() => {
    try {
      const jsonSchema = spec && spec.connection_specification ? spec.connection_specification : EMPTY_SCHEMA;
      const formFields = jsonSchemaToFormBlock(jsonSchema);
      const validationSchema = buildYupFormForJsonSchema(jsonSchema, formFields, formatMessage);
      validationSchema.validateSync(testingValues, { abortEarly: false });
      return 0;
    } catch (e) {
      if (ValidationError.isError(e)) {
        return e.errors.length;
      }
      return 1;
    }
  }, [spec, formatMessage, testingValues]);
}

export const StreamTestingPanel: React.FC<unknown> = () => {
  const { isTestingValuesInputOpen, setTestingValuesInputOpen } = useConnectorBuilderFormManagementState();
  const { jsonManifest, yamlEditorIsMounted } = useConnectorBuilderFormState();
  const mode = useBuilderWatch("mode");
  const { theme } = useAirbyteTheme();
  const testingValues = useBuilderWatch("testingValues");
  const testingValuesErrors = useTestingValuesErrors(testingValues, jsonManifest.spec);

  if (!yamlEditorIsMounted) {
    return (
      <div className={styles.loadingSpinner}>
        <Spinner />
      </div>
    );
  }

  const hasStreams = jsonManifest.streams?.length > 0;

  return (
    <div className={styles.container}>
      <TestingValuesMenu
        testingValuesErrors={testingValuesErrors}
        isOpen={isTestingValuesInputOpen}
        setIsOpen={setTestingValuesInputOpen}
      />
      {hasStreams || mode === "yaml" ? (
        <>
          <StreamSelector className={styles.streamSelector} />
          <StreamTester
            hasTestingValuesErrors={testingValuesErrors > 0}
            setTestingValuesInputOpen={setTestingValuesInputOpen}
          />
        </>
      ) : (
        <div className={styles.addStreamMessage}>
          <img
            className={styles.logo}
            alt=""
            src={theme === "airbyteThemeLight" ? addStreamScreenshotLight : addStreamScreenshotDark}
            width={320}
          />
          <Heading as="h2" className={styles.addStreamHeading}>
            <FormattedMessage id="connectorBuilder.noStreamsMessage" />
          </Heading>
        </div>
      )}
    </div>
  );
};

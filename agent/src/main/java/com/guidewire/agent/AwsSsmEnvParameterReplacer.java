package com.guidewire.agent;

import java.util.HashMap;
import java.util.Map;
import jetbrains.buildServer.agent.AgentLifeCycleAdapter;
import jetbrains.buildServer.agent.AgentLifeCycleListener;
import jetbrains.buildServer.agent.AgentRunningBuild;
import jetbrains.buildServer.agent.BuildProgressLogger;
import jetbrains.buildServer.util.EventDispatcher;
import org.jetbrains.annotations.NotNull;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.ssm.model.GetParameterRequest;
import software.amazon.awssdk.services.ssm.model.GetParameterResponse;

/**
 * This class dynamically retrieves the value of TeamCity parameters (Configuration Parameters,
 * System Properties, and Environment Variables), whose values are in the format of
 * %aws-ssm:secret-name% from AWS Parameter Store and update the value of the parameter to the actual value
 * in password type in the build.
 *
 */
public class AwsSsmEnvParameterReplacer extends AgentLifeCycleAdapter {

  public static final String AWS_ACCESS_KEY_CONFIG_FILE_PARAM = "aws_ssm_access_key_id";
  public static final String AWS_SECRET_KEY_CONFIG_FILE_PARAM = "aws_ssm_secret_access_key";
  public static final String AWS_REGION_CONFIG_FILE_PARAM = "aws_region";

  public static final String PARAMETER_PREFIX = "%aws-ssm:";
  public static final String PARAMETER_SUFFIX = "%";
  public static final String ENV_PARAMETER_PREFIX = "env";
  public static final String SYSTEM_PARAMETER_PREFIX = "system";

  public AwsSsmEnvParameterReplacer(@NotNull final EventDispatcher<AgentLifeCycleListener> eventDispatcher) {
    eventDispatcher.addListener(this);
  }

  @Override
  public void buildStarted(@NotNull AgentRunningBuild runningBuild) {
    updateBuildParameters(runningBuild);
  }

  protected static void updateBuildParameters(@NotNull AgentRunningBuild runningBuild) {
    BuildProgressLogger buildProgressLogger = runningBuild.getBuildLogger();
    buildProgressLogger.message("teamcity-aws-ssm-plugin starting...");
    try {
      String awsAccessKey = runningBuild.getSharedConfigParameters().get(AWS_ACCESS_KEY_CONFIG_FILE_PARAM);
      String awsSecretAccessKey = runningBuild.getSharedConfigParameters().get(AWS_SECRET_KEY_CONFIG_FILE_PARAM);
      String awsRegion = runningBuild.getSharedConfigParameters().get(AWS_REGION_CONFIG_FILE_PARAM);

      Map<String, String> buildEnvSysParams = runningBuild.getSharedBuildParameters().getAllParameters();
      Map<String, String> buildConfigParams = runningBuild.getSharedConfigParameters();
      Map<String, String> buildParams = new HashMap<>();

      buildParams.putAll(buildConfigParams);
      buildParams.putAll(buildEnvSysParams);
      Map<String, String> awsSsmParams = getSsmParameters(buildParams, buildProgressLogger);

      // If no AWS SSM parameters were found, we can just exit early.
      if (awsSsmParams.isEmpty()) {
        return;
      }

      AwsBasicCredentials awsBasicCredentials = AwsBasicCredentials.create(awsAccessKey, awsSecretAccessKey);
      StaticCredentialsProvider staticCredentialsProvider = StaticCredentialsProvider.create(awsBasicCredentials);

      try (SsmClient ssmClient = SsmClient.builder()
          .credentialsProvider(staticCredentialsProvider)
          .region(Region.of(awsRegion))
          .build()) {
        // Iterate over each parameter and set the value from SSM
        for (Map.Entry<String, String> kv : awsSsmParams.entrySet()) {
          GetParameterRequest getParamReq = GetParameterRequest.builder()
              .name(kv.getValue())
              .withDecryption(true)
              .build();

          buildProgressLogger.message("Requesting ssm parameter value for " + kv.getKey());
          GetParameterResponse getParamResp = ssmClient.getParameter(getParamReq);
          buildProgressLogger.message("SSM request completed for " + kv.getKey());
          String ssmValue = getParamResp.parameter().value();
          kv.setValue(ssmValue);
        }
      }

      replaceBuildParams(runningBuild, awsSsmParams);
    } catch (AwsServiceException e) {
      buildProgressLogger.message("AwsServiceException while fetching parameter." + e.getMessage());
    } catch (SdkClientException e) {
      buildProgressLogger.message("SdkClientException while fetching parameter." + e.getMessage());
    } catch (Exception e) {
      buildProgressLogger.message("Exception while fetching parameter." + e.getMessage());
    }

    buildProgressLogger.message("teamcity-aws-ssm-plugin finished");
  }

  public static void replaceBuildParams(@NotNull AgentRunningBuild runningBuild, Map<String, String> awsSsmParams) {
    for (Map.Entry<String, String> kv : awsSsmParams.entrySet()) {
      if (kv.getKey().startsWith(ENV_PARAMETER_PREFIX)) {
        String envVar = kv.getKey().substring(4);
        runningBuild.addSharedEnvironmentVariable(envVar, kv.getValue());
      } else if (kv.getKey().startsWith(SYSTEM_PARAMETER_PREFIX)) {
        String envVar = kv.getKey().substring(7);
        runningBuild.addSharedSystemProperty(envVar, kv.getValue());
      } else {
        runningBuild.addSharedConfigParameter(kv.getKey(), kv.getValue());
      }
      runningBuild.getPasswordReplacer().addPassword(kv.getValue());
    }
  }

  /**
   *   This method will turn a map of SOMETHING = %aws-ssm:some/secret% into SOMETHING = some/secret
   *    All non-aws-ssm parameters should not be returned
   *    Also the %aws-ssm: and % should be removed from the value
   *    The key should remain the same
   *    Example:
   *    input == {
   *      "env.SECRET":  "%aws-ssm:super/secret%",
   *      "env.DB_PASS": "%aws-ssm:db/mysql/username%",
   *      "TEAMCITY_BUILD": "22"
   *    }
   *    output == {
   *      "env.SECRET":  "super/secret",
   *      "env.DB_PASS": "db/mysql/username"
   *    }
   * @param tcParameters All parameters retrieved from the TeamCity
   * @param buildProgressLogger Logger to use for messages sent to the build log on server.
   * @return The map of parameters needed to be retrieved from AWS Parameter Store.
   */
  protected static Map<String, String> getSsmParameters(Map<String, String> tcParameters, BuildProgressLogger buildProgressLogger) {
    Map<String, String> ssmParamNames = new HashMap<>();

    for (Map.Entry<String, String> kv : tcParameters.entrySet()) {

      if (kv.getValue().startsWith(PARAMETER_PREFIX) && kv.getValue().endsWith(PARAMETER_SUFFIX)) {
        // This value represents that this parameter needs to be replaced
        String id = kv.getValue().trim();
        id = id.substring(PARAMETER_PREFIX.length());
        id = id.substring(0, id.length() - PARAMETER_SUFFIX.length());

        ssmParamNames.put(kv.getKey(), id);
      }
    }
    buildProgressLogger.message("Detected ssm parameters: " + ssmParamNames.keySet());
    return ssmParamNames;
  }
}

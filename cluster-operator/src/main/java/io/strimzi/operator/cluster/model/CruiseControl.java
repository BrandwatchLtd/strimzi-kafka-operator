/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategy;
import io.fabric8.kubernetes.api.model.apps.DeploymentStrategyBuilder;
import io.fabric8.kubernetes.api.model.apps.RollingUpdateDeploymentBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyIngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyPeerBuilder;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.CruiseControlResources;
import io.strimzi.api.kafka.model.CruiseControlSpec;
import io.strimzi.api.kafka.model.InlineLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.Logging;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.CruiseControlTemplate;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.cruisecontrol.Capacity;
import io.strimzi.operator.cluster.model.securityprofiles.ContainerSecurityProviderContextImpl;
import io.strimzi.operator.cluster.model.securityprofiles.PodSecurityProviderContextImpl;
import io.strimzi.operator.cluster.operator.resource.cruisecontrol.CruiseControlConfigurationParameters;
import io.strimzi.operator.common.PasswordGenerator;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.OrderedProperties;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static io.strimzi.operator.cluster.model.CruiseControlConfiguration.CRUISE_CONTROL_DEFAULT_ANOMALY_DETECTION_GOALS;
import static io.strimzi.operator.cluster.model.CruiseControlConfiguration.CRUISE_CONTROL_GOALS;
import static io.strimzi.operator.cluster.model.VolumeUtils.createConfigMapVolume;
import static io.strimzi.operator.cluster.model.VolumeUtils.createSecretVolume;
import static io.strimzi.operator.cluster.model.VolumeUtils.createVolumeMount;

/**
 * Cruise Control model
 */
public class CruiseControl extends AbstractModel {
    protected static final String COMPONENT_TYPE = "cruise-control";
    protected static final String CRUISE_CONTROL_METRIC_REPORTER = "com.linkedin.kafka.cruisecontrol.metricsreporter.CruiseControlMetricsReporter";
    protected static final String CRUISE_CONTROL_CONTAINER_NAME = "cruise-control";

    // Fields used for Cruise Control API authentication
    /**
     * Name of the admin user
     */
    public static final String API_ADMIN_NAME = "admin";
    private static final String API_ADMIN_ROLE = "ADMIN";
    protected static final String API_USER_NAME = "user";
    private static final String API_USER_ROLE = "USER";

    /**
     * Key for the admin user password
     */
    public static final String API_ADMIN_PASSWORD_KEY = COMPONENT_TYPE + ".apiAdminPassword";
    private static final String API_USER_PASSWORD_KEY = COMPONENT_TYPE + ".apiUserPassword";
    private static final String API_AUTH_FILE_KEY = COMPONENT_TYPE + ".apiAuthFile";
    protected static final String API_HEALTHCHECK_PATH = "/kafkacruisecontrol/state";

    protected static final String TLS_CC_CERTS_VOLUME_NAME = "cc-certs";
    protected static final String TLS_CC_CERTS_VOLUME_MOUNT = "/etc/cruise-control/cc-certs/";
    protected static final String TLS_CA_CERTS_VOLUME_NAME = "cluster-ca-certs";
    protected static final String TLS_CA_CERTS_VOLUME_MOUNT = "/etc/cruise-control/cluster-ca-certs/";
    protected static final String LOG_AND_METRICS_CONFIG_VOLUME_NAME = "cruise-control-metrics-and-logging";
    protected static final String LOG_AND_METRICS_CONFIG_VOLUME_MOUNT = "/opt/cruise-control/custom-config/";
    protected static final String API_AUTH_CONFIG_VOLUME_NAME = "api-auth-config";
    protected static final String API_AUTH_CONFIG_VOLUME_MOUNT = "/opt/cruise-control/api-auth-config/";

    protected static final String API_AUTH_CREDENTIALS_FILE = API_AUTH_CONFIG_VOLUME_MOUNT + API_AUTH_FILE_KEY;

    protected static final String ENV_VAR_CRUISE_CONTROL_METRICS_ENABLED = "CRUISE_CONTROL_METRICS_ENABLED";

    // Configuration defaults
    protected static final int DEFAULT_REPLICAS = 1;
    protected static final boolean DEFAULT_CRUISE_CONTROL_METRICS_ENABLED = false;

    // Default probe settings (liveness and readiness) for health checks
    protected static final int DEFAULT_HEALTHCHECK_DELAY = 15;
    protected static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;

    private static final Probe DEFAULT_HEALTHCHECK_OPTIONS = new ProbeBuilder()
            .withInitialDelaySeconds(DEFAULT_HEALTHCHECK_DELAY)
            .withTimeoutSeconds(DEFAULT_HEALTHCHECK_TIMEOUT)
            .build();

    private String minInsyncReplicas = "1";
    private boolean sslEnabled;
    private boolean authEnabled;
    protected Capacity capacity;

    /**
     * Port of the Cruise Control REST API
     */
    public static final int REST_API_PORT = 9090;
    /* test */ static final String REST_API_PORT_NAME = "rest-api";

    /* test */ static final String MIN_INSYNC_REPLICAS = "min.insync.replicas";

    // Cruise Control configuration keys (EnvVariables)
    protected static final String ENV_VAR_CRUISE_CONTROL_CONFIGURATION = "CRUISE_CONTROL_CONFIGURATION";
    protected static final String ENV_VAR_STRIMZI_KAFKA_BOOTSTRAP_SERVERS = "STRIMZI_KAFKA_BOOTSTRAP_SERVERS";
    protected static final String ENV_VAR_MIN_INSYNC_REPLICAS = "MIN_INSYNC_REPLICAS";

    protected static final String ENV_VAR_CRUISE_CONTROL_CAPACITY_CONFIGURATION = "CRUISE_CONTROL_CAPACITY_CONFIGURATION";

    protected static final String ENV_VAR_API_SSL_ENABLED = "STRIMZI_CC_API_SSL_ENABLED";
    protected static final String ENV_VAR_API_AUTH_ENABLED = "STRIMZI_CC_API_AUTH_ENABLED";
    protected static final String ENV_VAR_API_USER = "API_USER";
    protected static final String ENV_VAR_API_PORT = "API_PORT";
    protected static final String ENV_VAR_API_HEALTHCHECK_PATH = "API_HEALTHCHECK_PATH";

    protected static final String CO_ENV_VAR_CUSTOM_CRUISE_CONTROL_POD_LABELS = "STRIMZI_CUSTOM_CRUISE_CONTROL_LABELS";

    // Templates
    protected List<ContainerEnvVar> templateCruiseControlContainerEnvVars;
    protected SecurityContext templateCruiseControlContainerSecurityContext;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_CRUISE_CONTROL_POD_LABELS);
        if (value != null) {
            DEFAULT_POD_LABELS.putAll(Util.parseMap(value));
        }
    }

    /**
     * Constructor
     *
     * @param reconciliation The reconciliation
     * @param resource  Kubernetes resource with metadata containing the namespace and cluster name
     */
    protected CruiseControl(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, CruiseControlResources.deploymentName(resource.getMetadata().getName()), COMPONENT_TYPE);

        this.serviceName = CruiseControlResources.serviceName(cluster);
        this.ancillaryConfigMapName = CruiseControlResources.logAndMetricsConfigMapName(cluster);
        this.replicas = DEFAULT_REPLICAS;
        this.livenessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.readinessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.mountPath = "/var/lib/kafka";
        this.logAndMetricsConfigVolumeName = LOG_AND_METRICS_CONFIG_VOLUME_NAME;
        this.logAndMetricsConfigMountPath = LOG_AND_METRICS_CONFIG_VOLUME_MOUNT;
        this.isMetricsEnabled = DEFAULT_CRUISE_CONTROL_METRICS_ENABLED;
    }

    /**
     * Creates an instance of the Cruise Control model from the custom resource. When Cruise Control is not enabled,
     * this will return null.
     *
     * @param reconciliation    Reconciliation marker used for logging
     * @param kafkaCr           The Kafka custom resource
     * @param versions          Supported Kafka versions
     * @param storage           The actual storage configuration used by the cluster. This might differ from the storage
     *                          configuration configured by the user in the Kafka CR due to unallowed changes.
     *
     * @return                  Instance of the Cruise Control model
     */
    @SuppressWarnings({"checkstyle:NPathComplexity", "checkstyle:CyclomaticComplexity"})
    public static CruiseControl fromCrd(Reconciliation reconciliation, Kafka kafkaCr, KafkaVersion.Lookup versions, Storage storage) {
        CruiseControlSpec ccSpec = kafkaCr.getSpec().getCruiseControl();
        KafkaClusterSpec kafkaClusterSpec = kafkaCr.getSpec().getKafka();

        if (ccSpec != null) {
            CruiseControl cruiseControl = new CruiseControl(reconciliation, kafkaCr);

            cruiseControl.setReplicas(DEFAULT_REPLICAS);

            String image = ccSpec.getImage();
            if (image == null) {
                image = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_CRUISE_CONTROL_IMAGE, versions.kafkaImage(kafkaClusterSpec.getImage(), versions.defaultVersion().version()));
            }
            cruiseControl.setImage(image);

            cruiseControl.updateConfiguration(ccSpec);
            CruiseControlConfiguration ccConfiguration = (CruiseControlConfiguration) cruiseControl.getConfiguration();
            cruiseControl.sslEnabled = ccConfiguration.isApiSslEnabled();
            cruiseControl.authEnabled = ccConfiguration.isApiAuthEnabled();

            KafkaConfiguration configuration = new KafkaConfiguration(reconciliation, kafkaClusterSpec.getConfig().entrySet());
            if (configuration.getConfigOption(MIN_INSYNC_REPLICAS) != null) {
                cruiseControl.minInsyncReplicas = configuration.getConfigOption(MIN_INSYNC_REPLICAS);
            }

            // To avoid illegal storage configurations provided by the user,
            // we rely on the storage configuration provided by the KafkaAssemblyOperator
            cruiseControl.capacity = new Capacity(reconciliation, kafkaCr.getSpec(), storage);

            // Parse different types of metrics configurations
            ModelUtils.parseMetrics(cruiseControl, ccSpec);

            if (ccSpec.getReadinessProbe() != null) {
                cruiseControl.setReadinessProbe(ccSpec.getReadinessProbe());
            }

            if (ccSpec.getLivenessProbe() != null) {
                cruiseControl.setLivenessProbe(ccSpec.getLivenessProbe());
            }

            Logging logging = ccSpec.getLogging();
            cruiseControl.setLogging(logging == null ? new InlineLogging() : logging);

            cruiseControl.setGcLoggingEnabled(ccSpec.getJvmOptions() == null ? DEFAULT_JVM_GC_LOGGING_ENABLED : ccSpec.getJvmOptions().isGcLoggingEnabled());
            cruiseControl.setJvmOptions(ccSpec.getJvmOptions());

            cruiseControl.setResources(ccSpec.getResources());

            if (ccSpec.getTemplate() != null) {
                CruiseControlTemplate template = ccSpec.getTemplate();

                ModelUtils.parsePodTemplate(cruiseControl, template.getPod());
                ModelUtils.parseInternalServiceTemplate(cruiseControl, template.getApiService());

                if (template.getDeployment() != null && template.getDeployment().getMetadata() != null) {
                    cruiseControl.templateDeploymentLabels = template.getDeployment().getMetadata().getLabels();
                    cruiseControl.templateDeploymentAnnotations = template.getDeployment().getMetadata().getAnnotations();
                }

                if (template.getCruiseControlContainer() != null && template.getCruiseControlContainer().getEnv() != null) {
                    cruiseControl.templateCruiseControlContainerEnvVars = template.getCruiseControlContainer().getEnv();
                }

                if (template.getCruiseControlContainer() != null && template.getCruiseControlContainer().getSecurityContext() != null) {
                    cruiseControl.templateCruiseControlContainerSecurityContext = template.getCruiseControlContainer().getSecurityContext();
                }

                if (template.getServiceAccount() != null && template.getServiceAccount().getMetadata() != null) {
                    cruiseControl.templateServiceAccountLabels = template.getServiceAccount().getMetadata().getLabels();
                    cruiseControl.templateServiceAccountAnnotations = template.getServiceAccount().getMetadata().getAnnotations();
                }
            }

            cruiseControl.templatePodLabels = Util.mergeLabelsOrAnnotations(cruiseControl.templatePodLabels, DEFAULT_POD_LABELS);

            return cruiseControl;
        } else {
            return null;
        }
    }

    private void updateConfiguration(CruiseControlSpec spec) {
        CruiseControlConfiguration userConfiguration = new CruiseControlConfiguration(reconciliation, spec.getConfig().entrySet());
        for (Map.Entry<String, String> defaultEntry : CruiseControlConfiguration.getCruiseControlDefaultPropertiesMap().entrySet()) {
            if (userConfiguration.getConfigOption(defaultEntry.getKey()) == null) {
                userConfiguration.setConfigOption(defaultEntry.getKey(), defaultEntry.getValue());
            }
        }
        // Ensure that the configured anomaly.detection.goals are a sub-set of the default goals
        checkGoals(userConfiguration);
        this.setConfiguration(userConfiguration);
    }

    /**
     *  This method ensures that the checks in cruise-control/src/main/java/com/linkedin/kafka/cruisecontrol/config/KafkaCruiseControlConfig.java
     *  sanityCheckGoalNames() method (L118)  don't fail if a user submits custom default goals that have less members then the default
     *  anomaly.detection.goals.
     * @param configuration The configuration instance to be checked.
     * @throws UnsupportedOperationException If the configuration contains self.healing.goals configurations.
     */
    public void checkGoals(CruiseControlConfiguration configuration) {
        // If self healing goals are defined then these take precedence.
        // Right now, self.healing.goals must either be null or an empty list
        if (configuration.getConfigOption(CruiseControlConfigurationParameters.SELF_HEALING_CONFIG_KEY.toString()) != null) {
            String selfHealingGoalsString = configuration.getConfigOption(CruiseControlConfigurationParameters.SELF_HEALING_CONFIG_KEY.toString());
            List<String> selfHealingGoals = Arrays.asList(selfHealingGoalsString.split("\\s*,\\s*"));
            if (!selfHealingGoals.isEmpty()) {
                throw new UnsupportedOperationException("Cruise Control's self healing functionality is not currently supported. Please remove " +
                        CruiseControlConfigurationParameters.SELF_HEALING_CONFIG_KEY + " config");
            }
        }

        // If no anomaly detection goals have been defined by the user, the defaults defined in Cruise Control will be used.
        String anomalyGoalsString = configuration.getConfigOption(CruiseControlConfigurationParameters.ANOMALY_DETECTION_CONFIG_KEY.toString(), CRUISE_CONTROL_DEFAULT_ANOMALY_DETECTION_GOALS);
        Set<String> anomalyDetectionGoals = new HashSet<>(Arrays.asList(anomalyGoalsString.split("\\s*,\\s*")));

        String defaultGoalsString = configuration.getConfigOption(CruiseControlConfigurationParameters.DEFAULT_GOALS_CONFIG_KEY.toString(), CRUISE_CONTROL_GOALS);
        Set<String> defaultGoals = new HashSet<>(Arrays.asList(defaultGoalsString.split("\\s*,\\s*")));

        // Remove all the goals which are present in the default goals set from the anomaly detection goals
        anomalyDetectionGoals.removeAll(defaultGoals);

        if (!anomalyDetectionGoals.isEmpty()) {
            // If the anomaly detection goals contain goals which are not in the default goals then the CC startup
            // checks will fail, so we make the anomaly goals match the default goals
            configuration.setConfigOption(CruiseControlConfigurationParameters.ANOMALY_DETECTION_CONFIG_KEY.toString(), defaultGoalsString);
            LOGGER.warnCr(reconciliation, "Anomaly goals contained goals which are not in the configured default goals. Anomaly goals have " +
                    "been changed to match the specified default goals.");
        }
    }

    /**
     * @return  Generates a Kuberneets Service for Cruise Control
     */
    public Service generateService() {
        return createService("ClusterIP", List.of(createServicePort(REST_API_PORT_NAME, REST_API_PORT, REST_API_PORT, "TCP")), templateServiceAnnotations);
    }

    protected List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(1);

        portList.add(createContainerPort(REST_API_PORT_NAME, REST_API_PORT, "TCP"));

        if (isMetricsEnabled) {
            portList.add(createContainerPort(METRICS_PORT_NAME, METRICS_PORT, "TCP"));
        }

        return portList;
    }

    protected List<Volume> getVolumes(boolean isOpenShift) {
        return List.of(createTempDirVolume(),
                createSecretVolume(TLS_CC_CERTS_VOLUME_NAME, CruiseControlResources.secretName(cluster), isOpenShift),
                createSecretVolume(TLS_CA_CERTS_VOLUME_NAME, AbstractModel.clusterCaCertSecretName(cluster), isOpenShift),
                createSecretVolume(API_AUTH_CONFIG_VOLUME_NAME, CruiseControlResources.apiSecretName(cluster), isOpenShift),
                createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigMapName));
    }

    protected List<VolumeMount> getVolumeMounts() {
        return List.of(createTempDirVolumeMount(),
                createVolumeMount(CruiseControl.TLS_CC_CERTS_VOLUME_NAME, CruiseControl.TLS_CC_CERTS_VOLUME_MOUNT),
                createVolumeMount(CruiseControl.TLS_CA_CERTS_VOLUME_NAME, CruiseControl.TLS_CA_CERTS_VOLUME_MOUNT),
                createVolumeMount(CruiseControl.API_AUTH_CONFIG_VOLUME_NAME, CruiseControl.API_AUTH_CONFIG_VOLUME_MOUNT),
                createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));
    }

    /**
     * Generates Kubernetes Deployment for Cruise Cotnrol
     *
     * @param isOpenShift       Flag indicating if we are on OpenShift or not
     * @param imagePullPolicy   Image pull policy
     * @param imagePullSecrets  Image pull secrets
     *
     * @return  Cruise Control Kubernetes Deployment
     */
    public Deployment generateDeployment(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        DeploymentStrategy updateStrategy = new DeploymentStrategyBuilder()
                .withType("RollingUpdate")
                .withRollingUpdate(new RollingUpdateDeploymentBuilder()
                        .withMaxSurge(new IntOrString(1))
                        .withMaxUnavailable(new IntOrString(0))
                        .build())
                .build();

        return createDeployment(
                updateStrategy,
                Collections.emptyMap(),
                Collections.emptyMap(),
                getMergedAffinity(),
                getInitContainers(imagePullPolicy),
                getContainers(imagePullPolicy),
                getVolumes(isOpenShift),
                imagePullSecrets,
                securityProvider.cruiseControlPodSecurityContext(new PodSecurityProviderContextImpl(templateSecurityContext)));
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
        Container container = new ContainerBuilder()
                .withName(CRUISE_CONTROL_CONTAINER_NAME)
                .withImage(getImage())
                .withCommand("/opt/cruise-control/cruise_control_run.sh")
                .withEnv(getEnvVars())
                .withPorts(getContainerPortList())
                .withLivenessProbe(ProbeGenerator.defaultBuilder(livenessProbeOptions)
                        .withNewExec()
                            .withCommand("/opt/cruise-control/cruise_control_healthcheck.sh")
                        .endExec()
                        .build())
                .withReadinessProbe(ProbeGenerator.defaultBuilder(readinessProbeOptions)
                        .withNewExec()
                            .withCommand("/opt/cruise-control/cruise_control_healthcheck.sh")
                        .endExec()
                        .build())
                .withResources(getResources())
                .withVolumeMounts(getVolumeMounts())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .withSecurityContext(securityProvider.cruiseControlContainerSecurityContext(new ContainerSecurityProviderContextImpl(templateCruiseControlContainerSecurityContext)))
                .build();

        return Collections.singletonList(container);
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();

        varList.add(buildEnvVar(ENV_VAR_CRUISE_CONTROL_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_KAFKA_BOOTSTRAP_SERVERS, KafkaResources.bootstrapServiceName(cluster) + ":" + KafkaCluster.REPLICATION_PORT));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));
        varList.add(buildEnvVar(ENV_VAR_MIN_INSYNC_REPLICAS, String.valueOf(minInsyncReplicas)));

        varList.add(buildEnvVar(ENV_VAR_CRUISE_CONTROL_CAPACITY_CONFIGURATION, capacity.toString()));

        varList.add(buildEnvVar(ENV_VAR_API_SSL_ENABLED,  String.valueOf(this.sslEnabled)));
        varList.add(buildEnvVar(ENV_VAR_API_AUTH_ENABLED,  String.valueOf(this.authEnabled)));
        varList.add(buildEnvVar(ENV_VAR_API_USER,  API_USER_NAME));
        varList.add(buildEnvVar(ENV_VAR_API_PORT,  String.valueOf(REST_API_PORT)));
        varList.add(buildEnvVar(ENV_VAR_API_HEALTHCHECK_PATH, API_HEALTHCHECK_PATH));

        ModelUtils.heapOptions(varList, 75, 0L, getJvmOptions(), getResources());
        ModelUtils.jvmPerformanceOptions(varList, getJvmOptions());
        ModelUtils.jvmSystemProperties(varList, getJvmOptions());

        if (configuration != null && !configuration.getConfiguration().isEmpty()) {
            varList.add(buildEnvVar(ENV_VAR_CRUISE_CONTROL_CONFIGURATION, configuration.getConfiguration()));
        }

        // Add shared environment variables used for all containers
        varList.addAll(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateCruiseControlContainerEnvVars);

        return varList;
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return "cruiseControlDefaultLoggingProperties";
    }

    @Override
    protected String getServiceAccountName() {
        return CruiseControlResources.serviceAccountName(cluster);
    }

    /**
     * Creates Cruise Control API auth usernames, passwords, and credentials file
     *
     * @return Map containing Cruise Control API auth credentials
     */
    public static Map<String, String> generateCruiseControlApiCredentials() {
        PasswordGenerator passwordGenerator = new PasswordGenerator(16);
        String apiAdminPassword = passwordGenerator.generate();
        String apiUserPassword = passwordGenerator.generate();

        /*
         * Create Cruise Control API auth credentials file following Jetty's
         *  HashLoginService's file format: username: password [,rolename ...]
         */
        String authCredentialsFile =
                API_ADMIN_NAME + ": " + apiAdminPassword + "," + API_ADMIN_ROLE + "\n" +
                API_USER_NAME + ": " + apiUserPassword + "," + API_USER_ROLE + "\n";

        Map<String, String> data = new HashMap<>(3);
        data.put(API_ADMIN_PASSWORD_KEY, Util.encodeToBase64(apiAdminPassword));
        data.put(API_USER_PASSWORD_KEY, Util.encodeToBase64(apiUserPassword));
        data.put(API_AUTH_FILE_KEY, Util.encodeToBase64(authCredentialsFile));

        return data;
    }

    /**
     * Generate the Secret containing the Cruise Control API auth credentials.
     *
     * @return The generated Secret.
     */
    public Secret generateApiSecret() {
        return ModelUtils.createSecret(CruiseControlResources.apiSecretName(cluster), namespace, labels, ownerReference, generateCruiseControlApiCredentials(), Collections.emptyMap(), Collections.emptyMap());
    }

    /**
     * Generate the Secret containing the Cruise Control certificate signed by the cluster CA certificate used for TLS based
     * internal communication with Kafka
     * It also contains the related Cruise Control private key.
     *
     * @param namespace Namespace in which the Cruise Control cluster runs
     * @param kafkaName Name of the Kafka cluster (it is used for the SANs in the certificate)
     * @param clusterCa The cluster CA.
     * @param isMaintenanceTimeWindowsSatisfied Indicates whether we are in the maintenance window or not.
     *                                          This is used for certificate renewals
     *
     * @return The generated Secret.
     */
    public Secret generateCertificatesSecret(String namespace, String kafkaName, ClusterCa clusterCa, boolean isMaintenanceTimeWindowsSatisfied) {
        Map<String, CertAndKey> ccCerts = new HashMap<>(4);
        LOGGER.debugCr(reconciliation, "Generating certificates");
        try {
            ccCerts = clusterCa.generateCcCerts(namespace, kafkaName, isMaintenanceTimeWindowsSatisfied);
        } catch (IOException e) {
            LOGGER.warnCr(reconciliation, "Error while generating certificates", e);
        }
        LOGGER.debugCr(reconciliation, "End generating certificates");

        String keyCertName = "cruise-control";
        Map<String, String> data = new HashMap<>(4);

        CertAndKey cert = ccCerts.get(keyCertName);
        data.put(keyCertName + ".key", cert.keyAsBase64String());
        data.put(keyCertName + ".crt", cert.certAsBase64String());
        data.put(keyCertName + ".p12", cert.keyStoreAsBase64String());
        data.put(keyCertName + ".password", cert.storePasswordAsBase64String());

        return createSecret(CruiseControlResources.secretName(cluster), data,
                Collections.singletonMap(clusterCa.caCertGenerationAnnotation(), String.valueOf(clusterCa.certGeneration())));
    }

    /**
     * Generates the NetworkPolicies relevant for Cruise Control
     *
     * @param operatorNamespace                             Namespace where the Strimzi Cluster Operator runs. Null if not configured.
     * @param operatorNamespaceLabels                       Labels of the namespace where the Strimzi Cluster Operator runs. Null if not configured.
     *
     * @return The network policy.
     */
    public NetworkPolicy generateNetworkPolicy(String operatorNamespace, Labels operatorNamespaceLabels) {
        List<NetworkPolicyIngressRule> rules = new ArrayList<>(1);

        // CO can access the REST API
        NetworkPolicyIngressRule restApiRule = new NetworkPolicyIngressRuleBuilder()
                .addNewPort()
                    .withNewPort(REST_API_PORT)
                    .withProtocol("TCP")
                .endPort()
                .build();

        NetworkPolicyPeer clusterOperatorPeer = new NetworkPolicyPeerBuilder()
                .withNewPodSelector() // cluster operator
                .addToMatchLabels(Labels.STRIMZI_KIND_LABEL, "cluster-operator")
                .endPodSelector()
                .build();
        ModelUtils.setClusterOperatorNetworkPolicyNamespaceSelector(clusterOperatorPeer, namespace, operatorNamespace, operatorNamespaceLabels);

        restApiRule.setFrom(Collections.singletonList(clusterOperatorPeer));

        rules.add(restApiRule);

        if (isMetricsEnabled) {
            NetworkPolicyIngressRule metricsRule = new NetworkPolicyIngressRuleBuilder()
                    .addNewPort()
                        .withNewPort(METRICS_PORT)
                        .withProtocol("TCP")
                    .endPort()
                    .withFrom()
                    .build();

            rules.add(metricsRule);
        }

        NetworkPolicy networkPolicy = new NetworkPolicyBuilder()
                .withNewMetadata()
                    .withName(CruiseControlResources.networkPolicyName(cluster))
                    .withNamespace(namespace)
                    .withLabels(labels.toMap())
                    .withOwnerReferences(ownerReference)
                .endMetadata()
                .withNewSpec()
                    .withNewPodSelector()
                        .addToMatchLabels(Labels.STRIMZI_NAME_LABEL, CruiseControlResources.deploymentName(cluster))
                    .endPodSelector()
                .withIngress(rules)
                .endSpec()
                .build();

        LOGGER.traceCr(reconciliation, "Created network policy {}", networkPolicy);
        return networkPolicy;
    }

    @Override
    public String getAncillaryConfigMapKeyLogConfig() {
        return "log4j2.properties";
    }

    /**
     * Transforms properties to log4j2 properties file format and adds property for reloading the config
     *
     * @param properties map with properties
     *
     * @return modified string with monitorInterval
     */
    @Override
    public String createLog4jProperties(OrderedProperties properties) {
        if (!properties.asMap().containsKey("monitorInterval")) {
            properties.addPair("monitorInterval", "30");
        }
        return super.createLog4jProperties(properties);
    }
}

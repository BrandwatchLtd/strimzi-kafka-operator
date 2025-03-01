/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.AffinityBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.fabric8.kubernetes.api.model.rbac.ClusterRoleBinding;
import io.fabric8.kubernetes.api.model.rbac.RoleRef;
import io.fabric8.kubernetes.api.model.rbac.RoleRefBuilder;
import io.fabric8.kubernetes.api.model.rbac.Subject;
import io.fabric8.kubernetes.api.model.rbac.SubjectBuilder;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.ClientTls;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.KafkaBridge;
import io.strimzi.api.kafka.model.KafkaBridgeAdminClientSpec;
import io.strimzi.api.kafka.model.KafkaBridgeConsumerSpec;
import io.strimzi.api.kafka.model.KafkaBridgeHttpConfig;
import io.strimzi.api.kafka.model.KafkaBridgeProducerSpec;
import io.strimzi.api.kafka.model.KafkaBridgeResources;
import io.strimzi.api.kafka.model.KafkaBridgeSpec;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.Rack;
import io.strimzi.api.kafka.model.authentication.KafkaClientAuthentication;
import io.strimzi.api.kafka.model.template.KafkaBridgeTemplate;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.template.ResourceTemplate;
import io.strimzi.api.kafka.model.tracing.Tracing;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.securityprofiles.ContainerSecurityProviderContextImpl;
import io.strimzi.operator.cluster.model.securityprofiles.PodSecurityProviderContextImpl;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;
import io.strimzi.operator.common.model.Labels;
import io.strimzi.operator.common.model.OrderedProperties;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka Bridge model class
 */
public class KafkaBridgeCluster extends AbstractModel {
    /**
     * HTTP port configuration
     */
    public static final int DEFAULT_REST_API_PORT = 8080;

    /* test */ static final String COMPONENT_TYPE = "kafka-bridge";
    protected static final String REST_API_PORT_NAME = "rest-api";
    protected static final String TLS_CERTS_BASE_VOLUME_MOUNT = "/opt/strimzi/bridge-certs/";
    protected static final String PASSWORD_VOLUME_MOUNT = "/opt/strimzi/bridge-password/";
    protected static final String ENV_VAR_KAFKA_INIT_INIT_FOLDER_KEY = "INIT_FOLDER";

    // Configuration defaults
    protected static final int DEFAULT_REPLICAS = 1;
    protected static final int DEFAULT_HEALTHCHECK_DELAY = 15;
    protected static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    protected static final boolean DEFAULT_KAFKA_BRIDGE_METRICS_ENABLED = false;

    private static final Probe DEFAULT_HEALTHCHECK_OPTIONS = new ProbeBuilder()
            .withTimeoutSeconds(DEFAULT_HEALTHCHECK_TIMEOUT)
            .withInitialDelaySeconds(DEFAULT_HEALTHCHECK_DELAY).build();

    // Cluster Operator environment variables for custom discovery labels and annotations
    protected static final String CO_ENV_VAR_CUSTOM_SERVICE_LABELS = "STRIMZI_CUSTOM_KAFKA_BRIDGE_SERVICE_LABELS";
    protected static final String CO_ENV_VAR_CUSTOM_SERVICE_ANNOTATIONS = "STRIMZI_CUSTOM_KAFKA_BRIDGE_SERVICE_ANNOTATIONS";

    // Kafka Bridge configuration keys (EnvVariables)
    protected static final String ENV_VAR_PREFIX = "KAFKA_BRIDGE_";
    protected static final String ENV_VAR_KAFKA_BRIDGE_METRICS_ENABLED = "KAFKA_BRIDGE_METRICS_ENABLED";
    protected static final String ENV_VAR_KAFKA_BRIDGE_BOOTSTRAP_SERVERS = "KAFKA_BRIDGE_BOOTSTRAP_SERVERS";
    protected static final String ENV_VAR_KAFKA_BRIDGE_TLS = "KAFKA_BRIDGE_TLS";
    protected static final String ENV_VAR_KAFKA_BRIDGE_TRUSTED_CERTS = "KAFKA_BRIDGE_TRUSTED_CERTS";
    protected static final String ENV_VAR_KAFKA_BRIDGE_TLS_AUTH_CERT = "KAFKA_BRIDGE_TLS_AUTH_CERT";
    protected static final String ENV_VAR_KAFKA_BRIDGE_TLS_AUTH_KEY = "KAFKA_BRIDGE_TLS_AUTH_KEY";
    protected static final String ENV_VAR_KAFKA_BRIDGE_SASL_PASSWORD_FILE = "KAFKA_BRIDGE_SASL_PASSWORD_FILE";
    protected static final String ENV_VAR_KAFKA_BRIDGE_SASL_USERNAME = "KAFKA_BRIDGE_SASL_USERNAME";
    protected static final String ENV_VAR_KAFKA_BRIDGE_SASL_MECHANISM = "KAFKA_BRIDGE_SASL_MECHANISM";
    protected static final String ENV_VAR_KAFKA_BRIDGE_OAUTH_CONFIG = "KAFKA_BRIDGE_OAUTH_CONFIG";
    protected static final String ENV_VAR_KAFKA_BRIDGE_OAUTH_CLIENT_SECRET = "KAFKA_BRIDGE_OAUTH_CLIENT_SECRET";
    protected static final String ENV_VAR_KAFKA_BRIDGE_OAUTH_ACCESS_TOKEN = "KAFKA_BRIDGE_OAUTH_ACCESS_TOKEN";
    protected static final String ENV_VAR_KAFKA_BRIDGE_OAUTH_REFRESH_TOKEN = "KAFKA_BRIDGE_OAUTH_REFRESH_TOKEN";
    protected static final String ENV_VAR_KAFKA_BRIDGE_OAUTH_PASSWORD_GRANT_PASSWORD = "KAFKA_BRIDGE_OAUTH_PASSWORD_GRANT_PASSWORD";
    protected static final String OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT = "/opt/strimzi/oauth-certs/";
    protected static final String ENV_VAR_STRIMZI_TRACING = "STRIMZI_TRACING";

    protected static final String ENV_VAR_KAFKA_BRIDGE_ADMIN_CLIENT_CONFIG = "KAFKA_BRIDGE_ADMIN_CLIENT_CONFIG";
    protected static final String ENV_VAR_KAFKA_BRIDGE_PRODUCER_CONFIG = "KAFKA_BRIDGE_PRODUCER_CONFIG";
    protected static final String ENV_VAR_KAFKA_BRIDGE_CONSUMER_CONFIG = "KAFKA_BRIDGE_CONSUMER_CONFIG";
    protected static final String ENV_VAR_KAFKA_BRIDGE_ID = "KAFKA_BRIDGE_ID";

    protected static final String ENV_VAR_KAFKA_BRIDGE_HTTP_HOST = "KAFKA_BRIDGE_HTTP_HOST";
    protected static final String ENV_VAR_KAFKA_BRIDGE_HTTP_PORT = "KAFKA_BRIDGE_HTTP_PORT";
    protected static final String ENV_VAR_KAFKA_BRIDGE_CORS_ENABLED = "KAFKA_BRIDGE_CORS_ENABLED";
    protected static final String ENV_VAR_KAFKA_BRIDGE_CORS_ALLOWED_ORIGINS = "KAFKA_BRIDGE_CORS_ALLOWED_ORIGINS";
    protected static final String ENV_VAR_KAFKA_BRIDGE_CORS_ALLOWED_METHODS = "KAFKA_BRIDGE_CORS_ALLOWED_METHODS";

    protected static final String CO_ENV_VAR_CUSTOM_BRIDGE_POD_LABELS = "STRIMZI_CUSTOM_KAFKA_BRIDGE_LABELS";
    protected static final String INIT_VOLUME_MOUNT = "/opt/strimzi/init";
    private ClientTls tls;
    private KafkaClientAuthentication authentication;
    private KafkaBridgeHttpConfig http;
    private String bootstrapServers;
    private KafkaBridgeAdminClientSpec kafkaBridgeAdminClient;
    private KafkaBridgeConsumerSpec kafkaBridgeConsumer;
    private KafkaBridgeProducerSpec kafkaBridgeProducer;
    private List<ContainerEnvVar> templateContainerEnvVars;
    private List<ContainerEnvVar> templateInitContainerEnvVars;
    private SecurityContext templateContainerSecurityContext;

    // Templates
    private PodDisruptionBudgetTemplate templatePodDisruptionBudget;
    private ResourceTemplate templateInitClusterRoleBinding;
    private SecurityContext templateInitContainerSecurityContext;

    private Tracing tracing;

    private Rack rack;

    private String initImage;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_BRIDGE_POD_LABELS);
        if (value != null) {
            DEFAULT_POD_LABELS.putAll(Util.parseMap(value));
        }
    }

    /**
     * Constructor
     *
     * @param reconciliation The reconciliation
     * @param resource Kubernetes resource with metadata containing the namespace and cluster name
     */
    protected KafkaBridgeCluster(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, KafkaBridgeResources.deploymentName(resource.getMetadata().getName()), COMPONENT_TYPE);

        this.serviceName = KafkaBridgeResources.serviceName(cluster);
        this.ancillaryConfigMapName = KafkaBridgeResources.metricsAndLogConfigMapName(cluster);
        this.replicas = DEFAULT_REPLICAS;
        this.readinessPath = "/ready";
        this.livenessPath = "/healthy";
        this.livenessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.readinessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.isMetricsEnabled = DEFAULT_KAFKA_BRIDGE_METRICS_ENABLED;

        this.mountPath = "/var/lib/bridge";
        this.logAndMetricsConfigVolumeName = "kafka-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/strimzi/custom-config/";
    }

    /**
     * Create the KafkaBridge model instance from the KafkaBridge custom resource
     *
     * @param reconciliation Reconciliation marker
     * @param kafkaBridge    KafkaBridge custom resource
     * @return KafkaBridgeCluster instance
     */
    @SuppressWarnings({"checkstyle:NPathComplexity"})
    public static KafkaBridgeCluster fromCrd(Reconciliation reconciliation, KafkaBridge kafkaBridge) {

        KafkaBridgeCluster kafkaBridgeCluster = new KafkaBridgeCluster(reconciliation, kafkaBridge);

        KafkaBridgeSpec spec = kafkaBridge.getSpec();
        kafkaBridgeCluster.tracing = spec.getTracing();
        kafkaBridgeCluster.setResources(spec.getResources());
        kafkaBridgeCluster.setLogging(spec.getLogging());
        kafkaBridgeCluster.setGcLoggingEnabled(spec.getJvmOptions() == null ? DEFAULT_JVM_GC_LOGGING_ENABLED : spec.getJvmOptions().isGcLoggingEnabled());
        kafkaBridgeCluster.setJvmOptions(spec.getJvmOptions());
        String image = spec.getImage();
        if (image == null) {
            image = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KAFKA_BRIDGE_IMAGE, "quay.io/strimzi/kafka-bridge:latest");
        }
        kafkaBridgeCluster.setImage(image);
        kafkaBridgeCluster.setReplicas(spec.getReplicas());
        kafkaBridgeCluster.setBootstrapServers(spec.getBootstrapServers());
        kafkaBridgeCluster.setKafkaAdminClientConfiguration(spec.getAdminClient());
        kafkaBridgeCluster.setKafkaConsumerConfiguration(spec.getConsumer());
        kafkaBridgeCluster.setKafkaProducerConfiguration(spec.getProducer());
        kafkaBridgeCluster.rack = spec.getRack();
        String initImage = spec.getClientRackInitImage();
        if (initImage == null) {
            initImage = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KAFKA_INIT_IMAGE, "quay.io/strimzi/operator:latest");
        }
        kafkaBridgeCluster.initImage = initImage;
        if (kafkaBridge.getSpec().getLivenessProbe() != null) {
            kafkaBridgeCluster.setLivenessProbe(kafkaBridge.getSpec().getLivenessProbe());
        }

        if (kafkaBridge.getSpec().getReadinessProbe() != null) {
            kafkaBridgeCluster.setReadinessProbe(kafkaBridge.getSpec().getReadinessProbe());
        }

        kafkaBridgeCluster.setMetricsEnabled(spec.getEnableMetrics());

        kafkaBridgeCluster.setTls(spec.getTls() != null ? spec.getTls() : null);

        String warnMsg = AuthenticationUtils.validateClientAuthentication(spec.getAuthentication(), spec.getTls() != null);
        if (!warnMsg.isEmpty()) {
            LOGGER.warnCr(reconciliation, warnMsg);
        }
        kafkaBridgeCluster.setAuthentication(spec.getAuthentication());

        if (spec.getTemplate() != null) {
            fromCrdTemplate(kafkaBridgeCluster, spec);
        }

        kafkaBridgeCluster.templatePodLabels = Util.mergeLabelsOrAnnotations(kafkaBridgeCluster.templatePodLabels, DEFAULT_POD_LABELS);
        if (spec.getHttp() != null) {
            kafkaBridgeCluster.setKafkaBridgeHttpConfig(spec.getHttp());
        } else {
            LOGGER.warnCr(reconciliation, "The spec.http property is missing.");
            throw new InvalidResourceException("The HTTP configuration for the bridge is not specified.");
        }

        return kafkaBridgeCluster;
    }

    private static void fromCrdTemplate(final KafkaBridgeCluster kafkaBridgeCluster, final KafkaBridgeSpec spec) {
        KafkaBridgeTemplate template = spec.getTemplate();

        ModelUtils.parseDeploymentTemplate(kafkaBridgeCluster, template.getDeployment());
        ModelUtils.parsePodTemplate(kafkaBridgeCluster, template.getPod());
        ModelUtils.parseInternalServiceTemplate(kafkaBridgeCluster, template.getApiService());

        if (template.getApiService() != null && template.getApiService().getMetadata() != null)  {
            kafkaBridgeCluster.templateServiceLabels = template.getApiService().getMetadata().getLabels();
            kafkaBridgeCluster.templateServiceAnnotations = template.getApiService().getMetadata().getAnnotations();
        }

        if (template.getBridgeContainer() != null && template.getBridgeContainer().getEnv() != null) {
            kafkaBridgeCluster.templateContainerEnvVars = template.getBridgeContainer().getEnv();
        }

        if (template.getBridgeContainer() != null && template.getBridgeContainer().getSecurityContext() != null) {
            kafkaBridgeCluster.templateContainerSecurityContext = template.getBridgeContainer().getSecurityContext();
        }

        if (template.getInitContainer() != null && template.getInitContainer().getEnv() != null) {
            kafkaBridgeCluster.templateInitContainerEnvVars = template.getInitContainer().getEnv();
        }

        if (template.getInitContainer() != null && template.getInitContainer().getSecurityContext() != null) {
            kafkaBridgeCluster.templateInitContainerSecurityContext = template.getInitContainer().getSecurityContext();
        }

        if (template.getServiceAccount() != null && template.getServiceAccount().getMetadata() != null) {
            kafkaBridgeCluster.templateServiceAccountLabels = template.getServiceAccount().getMetadata().getLabels();
            kafkaBridgeCluster.templateServiceAccountAnnotations = template.getServiceAccount().getMetadata().getAnnotations();
        }

        kafkaBridgeCluster.templatePodDisruptionBudget = template.getPodDisruptionBudget();
        kafkaBridgeCluster.templateInitClusterRoleBinding = template.getClusterRoleBinding();
    }

    /**
     * @return  Generates and returns the Kubernetes service for the Kafka Bridge
     */
    public Service generateService() {
        List<ServicePort> ports = new ArrayList<>(3);

        int port = DEFAULT_REST_API_PORT;
        if (http != null) {
            port = http.getPort();
        }

        ports.add(createServicePort(REST_API_PORT_NAME, port, port, "TCP"));

        return createDiscoverableService("ClusterIP", ports, Util.mergeLabelsOrAnnotations(templateServiceLabels, ModelUtils.getCustomLabelsOrAnnotations(CO_ENV_VAR_CUSTOM_SERVICE_LABELS)),
                Util.mergeLabelsOrAnnotations(getDiscoveryAnnotation(port), templateServiceAnnotations, ModelUtils.getCustomLabelsOrAnnotations(CO_ENV_VAR_CUSTOM_SERVICE_ANNOTATIONS)));
    }

    /**
     * Generates a JSON String with the discovery annotation for the bridge service
     *
     * @return  JSON with discovery annotation
     */
    /*test*/ Map<String, String> getDiscoveryAnnotation(int port) {
        JsonObject discovery = new JsonObject();
        discovery.put("port", port);
        discovery.put("tls", false);
        discovery.put("auth", "none");
        discovery.put("protocol", "http");

        JsonArray anno = new JsonArray();
        anno.add(discovery);

        return Collections.singletonMap(Labels.STRIMZI_DISCOVERY_LABEL, anno.encodePrettily());
    }

    protected List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(3);

        int port = DEFAULT_REST_API_PORT;
        if (http != null) {
            port = http.getPort();
        }

        portList.add(createContainerPort(REST_API_PORT_NAME, port, "TCP"));

        return portList;
    }

    protected List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(2);
        volumeList.add(createTempDirVolume());
        volumeList.add(VolumeUtils.createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigMapName));

        if (tls != null) {
            VolumeUtils.createSecretVolume(volumeList, tls.getTrustedCertificates(), isOpenShift);
        }
        if (rack != null) {
            volumeList.add(VolumeUtils.createEmptyDirVolume(INIT_VOLUME_NAME, "1Mi", "Memory"));
        }
        AuthenticationUtils.configureClientAuthenticationVolumes(authentication, volumeList, "oauth-certs", isOpenShift);
        return volumeList;
    }

    protected List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>(2);

        volumeMountList.add(createTempDirVolumeMount());
        volumeMountList.add(VolumeUtils.createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));
        if (tls != null) {
            VolumeUtils.createSecretVolumeMount(volumeMountList, tls.getTrustedCertificates(), TLS_CERTS_BASE_VOLUME_MOUNT);
        }
        if (rack != null) {
            volumeMountList.add(VolumeUtils.createVolumeMount(INIT_VOLUME_NAME, INIT_VOLUME_MOUNT));
        }
        AuthenticationUtils.configureClientAuthenticationVolumeMounts(authentication, volumeMountList, TLS_CERTS_BASE_VOLUME_MOUNT, PASSWORD_VOLUME_MOUNT, OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT, "oauth-certs");
        return volumeMountList;
    }

    /**
     * Generates the Bridge Kubernetes Deployment
     *
     * @param annotations       Map with annotations
     * @param isOpenShift       Flag indicating if we are on OpenShift or not
     * @param imagePullPolicy   Image pull policy configuration
     * @param imagePullSecrets  List of image pull secrets
     *
     * @return  Generated Kubernetes Deployment resource
     */
    public Deployment generateDeployment(Map<String, String> annotations, boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {
        return createDeployment(
                getDeploymentStrategy(),
                Collections.emptyMap(),
                annotations,
                getMergedAffinity(),
                getInitContainers(imagePullPolicy),
                getContainers(imagePullPolicy),
                getVolumes(isOpenShift),
                imagePullSecrets,
                securityProvider.bridgePodSecurityContext(new PodSecurityProviderContextImpl(templateSecurityContext)));
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {

        List<Container> containers = new ArrayList<>(1);

        Container container = new ContainerBuilder()
                .withName(componentName)
                .withImage(getImage())
                .withCommand("/opt/strimzi/bin/docker/kafka_bridge_run.sh")
                .withEnv(getEnvVars())
                .withPorts(getContainerPortList())
                .withLivenessProbe(ProbeGenerator.httpProbe(livenessProbeOptions, livenessPath, REST_API_PORT_NAME))
                .withReadinessProbe(ProbeGenerator.httpProbe(readinessProbeOptions, readinessPath, REST_API_PORT_NAME))
                .withVolumeMounts(getVolumeMounts())
                .withResources(getResources())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .withSecurityContext(securityProvider.bridgeContainerSecurityContext(new ContainerSecurityProviderContextImpl(templateContainerSecurityContext)))
                .build();

        containers.add(container);

        return containers;
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));
        ModelUtils.javaOptions(varList, getJvmOptions());

        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_BOOTSTRAP_SERVERS, bootstrapServers));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_ADMIN_CLIENT_CONFIG, kafkaBridgeAdminClient == null ? "" : new KafkaBridgeAdminClientConfiguration(reconciliation, kafkaBridgeAdminClient.getConfig().entrySet()).getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_CONSUMER_CONFIG, kafkaBridgeConsumer == null ? "" : new KafkaBridgeConsumerConfiguration(reconciliation, kafkaBridgeConsumer.getConfig().entrySet()).getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_PRODUCER_CONFIG, kafkaBridgeProducer == null ? "" : new KafkaBridgeProducerConfiguration(reconciliation, kafkaBridgeProducer.getConfig().entrySet()).getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_ID, cluster));

        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_HTTP_HOST, KafkaBridgeHttpConfig.HTTP_DEFAULT_HOST));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_HTTP_PORT, String.valueOf(http != null ? http.getPort() : KafkaBridgeHttpConfig.HTTP_DEFAULT_PORT)));

        if (http != null && http.getCors() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_CORS_ENABLED, "true"));

            if (http.getCors().getAllowedOrigins() != null) {
                varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_CORS_ALLOWED_ORIGINS, String.join(",", http.getCors().getAllowedOrigins())));
            }

            if (http.getCors().getAllowedMethods() != null) {
                varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_CORS_ALLOWED_METHODS, String.join(",", http.getCors().getAllowedMethods())));
            }
        } else {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_CORS_ENABLED, "false"));
        }

        if (tls != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_TLS, "true"));

            List<CertSecretSource> trustedCertificates = tls.getTrustedCertificates();

            if (trustedCertificates != null && trustedCertificates.size() > 0) {
                StringBuilder sb = new StringBuilder();
                boolean separator = false;
                for (CertSecretSource certSecretSource : trustedCertificates) {
                    if (separator) {
                        sb.append(";");
                    }
                    sb.append(certSecretSource.getSecretName() + "/" + certSecretSource.getCertificate());
                    separator = true;
                }
                varList.add(buildEnvVar(ENV_VAR_KAFKA_BRIDGE_TRUSTED_CERTS, sb.toString()));
            }
        }

        AuthenticationUtils.configureClientAuthenticationEnvVars(authentication, varList, name -> ENV_VAR_PREFIX + name);

        if (tracing != null) {
            varList.add(buildEnvVar(ENV_VAR_STRIMZI_TRACING, tracing.getType()));
        }

        // Add shared environment variables used for all containers
        varList.addAll(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateContainerEnvVars);

        return varList;
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return "kafkaBridgeDefaultLoggingProperties";
    }

    @Override
    public String getAncillaryConfigMapKeyLogConfig() {
        return "log4j2.properties";
    }

    /**
     * Set the HTTP configuration
     * @param kafkaBridgeHttpConfig HTTP configuration
     */
    protected void setKafkaBridgeHttpConfig(KafkaBridgeHttpConfig kafkaBridgeHttpConfig) {
        this.http = kafkaBridgeHttpConfig;
    }

    /**
     * Set the tls configuration with the certificate to trust
     *
     * @param tls trusted certificates list
     */
    protected void setTls(ClientTls tls) {
        this.tls = tls;
    }

    /**
     * Sets the configured authentication
     *
     * @param authetication Authentication configuration
     */
    protected void setAuthentication(KafkaClientAuthentication authetication) {
        this.authentication = authetication;
    }

    /**
     * Generates the PodDisruptionBudget
     *
     * @return The pod disruption budget.
     */
    public PodDisruptionBudget generatePodDisruptionBudget() {
        return PodDisruptionBudgetUtils.createPodDisruptionBudget(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget);
    }

    /**
     * Generates the PodDisruptionBudgetV1Beta1
     *
     * @return The pod disruption budget V1Beta1.
     */
    public io.fabric8.kubernetes.api.model.policy.v1beta1.PodDisruptionBudget generatePodDisruptionBudgetV1Beta1() {
        return PodDisruptionBudgetUtils.createPodDisruptionBudgetV1Beta1(componentName, namespace, labels, ownerReference, templatePodDisruptionBudget);
    }

    @Override
    protected String getServiceAccountName() {
        return KafkaBridgeResources.serviceAccountName(cluster);
    }

    /**
     * Set Kafka AdminClient's configuration
     * @param kafkaBridgeAdminClient configuration
     */
    protected void setKafkaAdminClientConfiguration(KafkaBridgeAdminClientSpec kafkaBridgeAdminClient) {
        this.kafkaBridgeAdminClient = kafkaBridgeAdminClient;
    }

    /**
     * Set Kafka consumer's configuration
     * @param kafkaBridgeConsumer configuration
     */
    protected void setKafkaConsumerConfiguration(KafkaBridgeConsumerSpec kafkaBridgeConsumer) {
        this.kafkaBridgeConsumer = kafkaBridgeConsumer;
    }

    /**
     * Set Kafka producer's configuration
     * @param kafkaBridgeProducer configuration
     */
    protected void setKafkaProducerConfiguration(KafkaBridgeProducerSpec kafkaBridgeProducer) {
        this.kafkaBridgeProducer = kafkaBridgeProducer;
    }

    /**
     * Set Bootstrap servers for connection to cluster
     * @param bootstrapServers bootstrap servers
     */
    protected void setBootstrapServers(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    /**
     * @return  The HTTP configuration of the Bridge
     */
    public KafkaBridgeHttpConfig getHttp() {
        return this.http;
    }

    /**
     * Returns a combined affinity: Adding the affinity needed for the "kafka-rack" to the {@link #getUserAffinity()}.
     */
    @Override
    protected Affinity getMergedAffinity() {
        Affinity userAffinity = getUserAffinity();
        AffinityBuilder builder = new AffinityBuilder(userAffinity == null ? new Affinity() : userAffinity);
        if (rack != null) {
            builder = ModelUtils.populateAffinityBuilderWithRackLabelSelector(builder, userAffinity, rack.getTopologyKey());
        }
        return builder.build();
    }

    /**
     * Creates the ClusterRoleBinding which is used to bind the Kafka Bridge SA to the ClusterRole
     * which permissions the Kafka init container to access K8S nodes (necessary for rack-awareness).
     *
     * @return The cluster role binding.
     */
    public ClusterRoleBinding generateClusterRoleBinding() {
        if (rack != null) {
            Subject subject = new SubjectBuilder()
                    .withKind("ServiceAccount")
                    .withName(getServiceAccountName())
                    .withNamespace(namespace)
                    .build();

            RoleRef roleRef = new RoleRefBuilder()
                    .withName("strimzi-kafka-client")
                    .withApiGroup("rbac.authorization.k8s.io")
                    .withKind("ClusterRole")
                    .build();

            return RbacUtils
                    .createClusterRoleBinding(KafkaBridgeResources.initContainerClusterRoleBindingName(cluster, namespace), roleRef, List.of(subject), labels, templateInitClusterRoleBinding);
        } else {
            return null;
        }
    }

    @Override
    protected List<Container> getInitContainers(ImagePullPolicy imagePullPolicy) {
        List<Container> initContainers = new ArrayList<>(1);

        if (rack != null) {
            Container initContainer = new ContainerBuilder()
                    .withName(INIT_NAME)
                    .withImage(initImage)
                    .withArgs("/opt/strimzi/bin/kafka_init_run.sh")
                    .withEnv(getInitContainerEnvVars())
                    .withVolumeMounts(VolumeUtils.createVolumeMount(INIT_VOLUME_NAME, INIT_VOLUME_MOUNT))
                    .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, initImage))
                    .withSecurityContext(securityProvider.bridgeInitContainerSecurityContext(new ContainerSecurityProviderContextImpl(templateInitContainerSecurityContext)))
                    .build();

            if (getResources() != null) {
                initContainer.setResources(getResources());
            }
            initContainers.add(initContainer);
        }

        return initContainers;
    }

    protected List<EnvVar> getInitContainerEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVarFromFieldRef(ENV_VAR_KAFKA_INIT_NODE_NAME, "spec.nodeName"));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_INIT_RACK_TOPOLOGY_KEY, rack.getTopologyKey()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_INIT_INIT_FOLDER_KEY, INIT_VOLUME_MOUNT));

        // Add shared environment variables used for all containers
        varList.addAll(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateInitContainerEnvVars);

        return varList;
    }


    /**
     * Transforms properties to log4j2 properties file format and adds property for reloading the config
     * @param properties map with properties
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

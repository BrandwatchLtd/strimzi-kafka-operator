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
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.policy.v1.PodDisruptionBudget;
import io.strimzi.api.kafka.model.CertSecretSource;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.KafkaMirrorMaker;
import io.strimzi.api.kafka.model.KafkaMirrorMakerConsumerSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMakerProducerSpec;
import io.strimzi.api.kafka.model.KafkaMirrorMakerResources;
import io.strimzi.api.kafka.model.KafkaMirrorMakerSpec;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.template.KafkaMirrorMakerTemplate;
import io.strimzi.api.kafka.model.template.PodDisruptionBudgetTemplate;
import io.strimzi.api.kafka.model.tracing.JaegerTracing;
import io.strimzi.api.kafka.model.tracing.OpenTelemetryTracing;
import io.strimzi.api.kafka.model.tracing.Tracing;
import io.strimzi.operator.cluster.model.securityprofiles.ContainerSecurityProviderContextImpl;
import io.strimzi.operator.cluster.model.securityprofiles.PodSecurityProviderContextImpl;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Kafka Mirror Maker 1 model
 */
@SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity"})
public class KafkaMirrorMakerCluster extends AbstractModel {
    protected static final String COMPONENT_TYPE = "kafka-mirror-maker";

    protected static final String TLS_CERTS_VOLUME_MOUNT_CONSUMER = "/opt/kafka/consumer-certs/";
    protected static final String PASSWORD_VOLUME_MOUNT_CONSUMER = "/opt/kafka/consumer-password/";
    protected static final String TLS_CERTS_VOLUME_MOUNT_PRODUCER = "/opt/kafka/producer-certs/";
    protected static final String PASSWORD_VOLUME_MOUNT_PRODUCER = "/opt/kafka/producer-password/";
    protected static final String OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT_CONSUMER = "/opt/kafka/consumer-oauth-certs/";
    protected static final String OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT_PRODUCER = "/opt/kafka/producer-oauth-certs/";

    // Configuration defaults
    private static final int DEFAULT_HEALTHCHECK_DELAY = 60;
    private static final int DEFAULT_HEALTHCHECK_TIMEOUT = 5;
    private static final int DEFAULT_HEALTHCHECK_PERIOD = 10;
    private static final Probe READINESS_PROBE_OPTIONS = new ProbeBuilder().withTimeoutSeconds(DEFAULT_HEALTHCHECK_TIMEOUT).withInitialDelaySeconds(DEFAULT_HEALTHCHECK_DELAY).build();
    protected static final boolean DEFAULT_KAFKA_MIRRORMAKER_METRICS_ENABLED = false;

    // Kafka Mirror Maker configuration keys (EnvVariables)
    protected static final String ENV_VAR_PREFIX = "KAFKA_MIRRORMAKER_";

    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_METRICS_ENABLED = "KAFKA_MIRRORMAKER_METRICS_ENABLED";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_CONSUMER = "KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_CONSUMER = "KAFKA_MIRRORMAKER_TLS_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_CONSUMER = "KAFKA_MIRRORMAKER_TRUSTED_CERTS_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_CERT_CONSUMER = "KAFKA_MIRRORMAKER_TLS_AUTH_CERT_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_KEY_CONSUMER = "KAFKA_MIRRORMAKER_TLS_AUTH_KEY_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_MECHANISM_CONSUMER = "KAFKA_MIRRORMAKER_SASL_MECHANISM_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_CONSUMER = "KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_USERNAME_CONSUMER = "KAFKA_MIRRORMAKER_SASL_USERNAME_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_GROUPID_CONSUMER = "KAFKA_MIRRORMAKER_GROUPID_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER = "KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_CONFIG_CONSUMER = "KAFKA_MIRRORMAKER_OAUTH_CONFIG_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_CLIENT_SECRET_CONSUMER = "KAFKA_MIRRORMAKER_OAUTH_CLIENT_SECRET_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_ACCESS_TOKEN_CONSUMER = "KAFKA_MIRRORMAKER_OAUTH_ACCESS_TOKEN_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_REFRESH_TOKEN_CONSUMER = "KAFKA_MIRRORMAKER_OAUTH_REFRESH_TOKEN_CONSUMER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_PASSWORD_GRANT_PASSWORD_CONSUMER = "KAFKA_MIRRORMAKER_OAUTH_PASSWORD_GRANT_PASSWORD_CONSUMER";

    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_PRODUCER = "KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_PRODUCER = "KAFKA_MIRRORMAKER_TLS_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_PRODUCER = "KAFKA_MIRRORMAKER_TRUSTED_CERTS_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_CERT_PRODUCER = "KAFKA_MIRRORMAKER_TLS_AUTH_CERT_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_TLS_AUTH_KEY_PRODUCER = "KAFKA_MIRRORMAKER_TLS_AUTH_KEY_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_MECHANISM_PRODUCER = "KAFKA_MIRRORMAKER_SASL_MECHANISM_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_PRODUCER = "KAFKA_MIRRORMAKER_SASL_PASSWORD_FILE_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_SASL_USERNAME_PRODUCER = "KAFKA_MIRRORMAKER_SASL_USERNAME_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER = "KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_CONFIG_PRODUCER = "KAFKA_MIRRORMAKER_OAUTH_CONFIG_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_CLIENT_SECRET_PRODUCER = "KAFKA_MIRRORMAKER_OAUTH_CLIENT_SECRET_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_ACCESS_TOKEN_PRODUCER = "KAFKA_MIRRORMAKER_OAUTH_ACCESS_TOKEN_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_REFRESH_TOKEN_PRODUCER = "KAFKA_MIRRORMAKER_OAUTH_REFRESH_TOKEN_PRODUCER";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OAUTH_PASSWORD_GRANT_PASSWORD_PRODUCER = "KAFKA_MIRRORMAKER_OAUTH_PASSWORD_GRANT_PASSWORD_PRODUCER";

    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_INCLUDE = "KAFKA_MIRRORMAKER_INCLUDE";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_NUMSTREAMS = "KAFKA_MIRRORMAKER_NUMSTREAMS";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_OFFSET_COMMIT_INTERVAL = "KAFKA_MIRRORMAKER_OFFSET_COMMIT_INTERVAL";
    protected static final String ENV_VAR_KAFKA_MIRRORMAKER_ABORT_ON_SEND_FAILURE = "KAFKA_MIRRORMAKER_ABORT_ON_SEND_FAILURE";

    protected static final String ENV_VAR_STRIMZI_READINESS_PERIOD = "STRIMZI_READINESS_PERIOD";
    protected static final String ENV_VAR_STRIMZI_LIVENESS_PERIOD = "STRIMZI_LIVENESS_PERIOD";
    protected static final String ENV_VAR_STRIMZI_TRACING = "STRIMZI_TRACING";

    protected static final String CO_ENV_VAR_CUSTOM_MIRROR_MAKER_POD_LABELS = "STRIMZI_CUSTOM_KAFKA_MIRROR_MAKER_LABELS";

    protected String include;
    protected Tracing tracing;

    protected KafkaMirrorMakerProducerSpec producer;
    protected KafkaMirrorMakerConsumerSpec consumer;

    // Templates
    private PodDisruptionBudgetTemplate templatePodDisruptionBudget;
    protected List<ContainerEnvVar> templateContainerEnvVars;
    protected SecurityContext templateContainerSecurityContext;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_MIRROR_MAKER_POD_LABELS);
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
    protected KafkaMirrorMakerCluster(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, KafkaMirrorMakerResources.deploymentName(resource.getMetadata().getName()), COMPONENT_TYPE);

        this.serviceName = KafkaMirrorMakerResources.serviceName(cluster);
        this.ancillaryConfigMapName = KafkaMirrorMakerResources.metricsAndLogConfigMapName(cluster);
        this.readinessPath = "/";
        this.readinessProbeOptions = READINESS_PROBE_OPTIONS;
        this.livenessPath = "/";
        this.livenessProbeOptions = READINESS_PROBE_OPTIONS;
        this.isMetricsEnabled = DEFAULT_KAFKA_MIRRORMAKER_METRICS_ENABLED;

        this.mountPath = "/var/lib/kafka";
        this.logAndMetricsConfigVolumeName = "kafka-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/kafka/custom-config/";
    }

    /**
     * Creates the Kafka Mirror Maker 1 model from the KafkaMirrorMaker CR
     *
     * @param reconciliation        Reconciliation marker
     * @param kafkaMirrorMaker      KafkaMirrorMaker custom resource
     * @param versions              Supported Kafka versions
     *
     * @return  Kafka Mirror Maker model instance
     */
    @SuppressWarnings("deprecation")
    public static KafkaMirrorMakerCluster fromCrd(Reconciliation reconciliation, KafkaMirrorMaker kafkaMirrorMaker, KafkaVersion.Lookup versions) {
        KafkaMirrorMakerCluster kafkaMirrorMakerCluster = new KafkaMirrorMakerCluster(reconciliation, kafkaMirrorMaker);

        KafkaMirrorMakerSpec spec = kafkaMirrorMaker.getSpec();
        if (spec != null) {
            kafkaMirrorMakerCluster.setReplicas(spec.getReplicas());
            kafkaMirrorMakerCluster.setResources(spec.getResources());

            if (spec.getReadinessProbe() != null) {
                kafkaMirrorMakerCluster.setReadinessProbe(spec.getReadinessProbe());
            }

            if (spec.getLivenessProbe() != null) {
                kafkaMirrorMakerCluster.setLivenessProbe(spec.getLivenessProbe());
            }

            String whitelist = spec.getWhitelist();
            String include = spec.getInclude();

            if (include == null && whitelist == null)   {
                throw new InvalidResourceException("One of the fields include or whitelist needs to be specified.");
            } else if (whitelist != null && include != null) {
                LOGGER.warnCr(reconciliation, "Both include and whitelist fields are present. Whitelist is deprecated and will be ignored.");
            }

            kafkaMirrorMakerCluster.include = include != null ? include : whitelist;

            String warnMsg = AuthenticationUtils.validateClientAuthentication(spec.getProducer().getAuthentication(), spec.getProducer().getTls() != null);
            if (!warnMsg.isEmpty()) {
                LOGGER.warnCr(reconciliation, warnMsg);
            }

            kafkaMirrorMakerCluster.producer = spec.getProducer();

            warnMsg = AuthenticationUtils.validateClientAuthentication(spec.getConsumer().getAuthentication(), spec.getConsumer().getTls() != null);
            if (!warnMsg.isEmpty()) {
                LOGGER.warnCr(reconciliation, warnMsg);
            }

            kafkaMirrorMakerCluster.consumer = spec.getConsumer();

            kafkaMirrorMakerCluster.setImage(versions.kafkaMirrorMakerImage(spec.getImage(), spec.getVersion()));

            kafkaMirrorMakerCluster.setLogging(spec.getLogging());
            kafkaMirrorMakerCluster.setGcLoggingEnabled(spec.getJvmOptions() == null ? DEFAULT_JVM_GC_LOGGING_ENABLED : spec.getJvmOptions().isGcLoggingEnabled());
            kafkaMirrorMakerCluster.setJvmOptions(spec.getJvmOptions());

            // Parse different types of metrics configurations
            ModelUtils.parseMetrics(kafkaMirrorMakerCluster, spec);

            if (spec.getTemplate() != null) {
                KafkaMirrorMakerTemplate template = spec.getTemplate();

                if (template.getMirrorMakerContainer() != null && template.getMirrorMakerContainer().getEnv() != null) {
                    kafkaMirrorMakerCluster.templateContainerEnvVars = template.getMirrorMakerContainer().getEnv();
                }

                if (template.getMirrorMakerContainer() != null && template.getMirrorMakerContainer().getSecurityContext() != null) {
                    kafkaMirrorMakerCluster.templateContainerSecurityContext = template.getMirrorMakerContainer().getSecurityContext();
                }

                if (template.getServiceAccount() != null && template.getServiceAccount().getMetadata() != null) {
                    kafkaMirrorMakerCluster.templateServiceAccountLabels = template.getServiceAccount().getMetadata().getLabels();
                    kafkaMirrorMakerCluster.templateServiceAccountAnnotations = template.getServiceAccount().getMetadata().getAnnotations();
                }

                ModelUtils.parseDeploymentTemplate(kafkaMirrorMakerCluster, template.getDeployment());
                ModelUtils.parsePodTemplate(kafkaMirrorMakerCluster, template.getPod());
                kafkaMirrorMakerCluster.templatePodDisruptionBudget = template.getPodDisruptionBudget();
            }

            kafkaMirrorMakerCluster.tracing = spec.getTracing();
        }
        kafkaMirrorMakerCluster.templatePodLabels = Util.mergeLabelsOrAnnotations(kafkaMirrorMakerCluster.templatePodLabels, DEFAULT_POD_LABELS);

        return kafkaMirrorMakerCluster;
    }

    protected List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>(1);
        if (isMetricsEnabled) {
            portList.add(createContainerPort(METRICS_PORT_NAME, METRICS_PORT, "TCP"));
        }

        return portList;
    }

    protected List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>(2);

        volumeList.add(createTempDirVolume());
        volumeList.add(VolumeUtils.createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigMapName));

        if (producer.getTls() != null) {
            VolumeUtils.createSecretVolume(volumeList, producer.getTls().getTrustedCertificates(), isOpenShift);
        }
        AuthenticationUtils.configureClientAuthenticationVolumes(producer.getAuthentication(), volumeList, "producer-oauth-certs", isOpenShift);

        if (consumer.getTls() != null) {
            VolumeUtils.createSecretVolume(volumeList, consumer.getTls().getTrustedCertificates(), isOpenShift);
        }
        AuthenticationUtils.configureClientAuthenticationVolumes(consumer.getAuthentication(), volumeList, "consumer-oauth-certs", isOpenShift);

        return volumeList;
    }

    protected List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>(2);

        volumeMountList.add(createTempDirVolumeMount());
        volumeMountList.add(VolumeUtils.createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));
        /* producer auth*/
        if (producer.getTls() != null) {
            VolumeUtils.createSecretVolumeMount(volumeMountList, producer.getTls().getTrustedCertificates(), TLS_CERTS_VOLUME_MOUNT_PRODUCER);
        }
        AuthenticationUtils.configureClientAuthenticationVolumeMounts(producer.getAuthentication(), volumeMountList, TLS_CERTS_VOLUME_MOUNT_PRODUCER, PASSWORD_VOLUME_MOUNT_PRODUCER, OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT_PRODUCER, "producer-oauth-certs");

        /* consumer auth*/
        if (consumer.getTls() != null) {
            VolumeUtils.createSecretVolumeMount(volumeMountList, consumer.getTls().getTrustedCertificates(), TLS_CERTS_VOLUME_MOUNT_CONSUMER);
        }
        AuthenticationUtils.configureClientAuthenticationVolumeMounts(consumer.getAuthentication(), volumeMountList, TLS_CERTS_VOLUME_MOUNT_CONSUMER, PASSWORD_VOLUME_MOUNT_CONSUMER, OAUTH_TLS_CERTS_BASE_VOLUME_MOUNT_CONSUMER, "consumer-oauth-certs");

        return volumeMountList;
    }

    /**
     * Generates Kafka Mirror Maker Deployment
     *
     * @param annotations       Map with annotations
     * @param isOpenShift       Flag indicating if we are on OpenShift
     * @param imagePullPolicy   Image pull policy
     * @param imagePullSecrets  List with image pull secrets
     *
     * @return  Generated Deployment
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
                securityProvider.kafkaMirrorMakerPodSecurityContext(new PodSecurityProviderContextImpl(templateSecurityContext)));
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {

        List<Container> containers = new ArrayList<>(1);

        Container container = new ContainerBuilder()
                .withName(componentName)
                .withImage(getImage())
                .withCommand("/opt/kafka/kafka_mirror_maker_run.sh")
                .withEnv(getEnvVars())
                .withPorts(getContainerPortList())
                .withLivenessProbe(ProbeGenerator.defaultBuilder(livenessProbeOptions)
                        .withNewExec()
                            .withCommand("/opt/kafka/kafka_mirror_maker_liveness.sh")
                        .endExec().build())
                .withReadinessProbe(ProbeGenerator.defaultBuilder(readinessProbeOptions)
                        .withNewExec()
                            // The mirror-maker-agent will create /tmp/mirror-maker-ready in the container
                            .withCommand("test", "-f", "/tmp/mirror-maker-ready")
                        .endExec().build())
                .withVolumeMounts(getVolumeMounts())
                .withResources(getResources())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .withSecurityContext(securityProvider.kafkaMirrorMakerContainerSecurityContext(new ContainerSecurityProviderContextImpl(templateContainerSecurityContext)))
                .build();

        containers.add(container);

        return containers;
    }

    @SuppressWarnings("deprecation")
    private KafkaMirrorMakerConsumerConfiguration getConsumerConfiguration() {
        KafkaMirrorMakerConsumerConfiguration config = new KafkaMirrorMakerConsumerConfiguration(reconciliation, consumer.getConfig().entrySet());

        if (tracing != null) {
            if (JaegerTracing.TYPE_JAEGER.equals(tracing.getType())) {
                config.setConfigOption("interceptor.classes", JaegerTracing.CONSUMER_INTERCEPTOR_CLASS_NAME);
            } else if (OpenTelemetryTracing.TYPE_OPENTELEMETRY.equals(tracing.getType())) {
                config.setConfigOption("interceptor.classes", OpenTelemetryTracing.CONSUMER_INTERCEPTOR_CLASS_NAME);
            }
        }

        return config;
    }

    @SuppressWarnings("deprecation")
    private KafkaMirrorMakerProducerConfiguration getProducerConfiguration()    {
        KafkaMirrorMakerProducerConfiguration config = new KafkaMirrorMakerProducerConfiguration(reconciliation, producer.getConfig().entrySet());

        if (tracing != null) {
            if (JaegerTracing.TYPE_JAEGER.equals(tracing.getType())) {
                config.setConfigOption("interceptor.classes", JaegerTracing.PRODUCER_INTERCEPTOR_CLASS_NAME);
            } else if (OpenTelemetryTracing.TYPE_OPENTELEMETRY.equals(tracing.getType())) {
                config.setConfigOption("interceptor.classes", OpenTelemetryTracing.PRODUCER_INTERCEPTOR_CLASS_NAME);
            }
        }

        return config;
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_CONSUMER,
                getConsumerConfiguration().getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_CONFIGURATION_PRODUCER,
                getProducerConfiguration().getConfiguration()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_CONSUMER, consumer.getBootstrapServers()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_BOOTSTRAP_SERVERS_PRODUCER, producer.getBootstrapServers()));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_INCLUDE, include));
        varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_GROUPID_CONSUMER, consumer.getGroupId()));
        if (consumer.getNumStreams() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_NUMSTREAMS, Integer.toString(consumer.getNumStreams())));
        }
        if (consumer.getOffsetCommitInterval() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_OFFSET_COMMIT_INTERVAL, Integer.toString(consumer.getOffsetCommitInterval())));
        }
        if (producer.getAbortOnSendFailure() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_ABORT_ON_SEND_FAILURE, Boolean.toString(producer.getAbortOnSendFailure())));
        }
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));

        if (tracing != null) {
            varList.add(buildEnvVar(ENV_VAR_STRIMZI_TRACING, tracing.getType()));
        }

        ModelUtils.heapOptions(varList, 75, 0L, getJvmOptions(), getResources());
        ModelUtils.jvmPerformanceOptions(varList, getJvmOptions());
        ModelUtils.jvmSystemProperties(varList, getJvmOptions());

        /* consumer */
        addConsumerEnvVars(varList);

        /* producer */
        addProducerEnvVars(varList);

        varList.add(buildEnvVar(ENV_VAR_STRIMZI_LIVENESS_PERIOD,
                String.valueOf(livenessProbeOptions.getPeriodSeconds() != null ? livenessProbeOptions.getPeriodSeconds() : DEFAULT_HEALTHCHECK_PERIOD)));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_READINESS_PERIOD,
                String.valueOf(readinessProbeOptions.getPeriodSeconds() != null ? readinessProbeOptions.getPeriodSeconds() : DEFAULT_HEALTHCHECK_PERIOD)));

        // Add shared environment variables used for all containers
        varList.addAll(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateContainerEnvVars);

        return varList;
    }

    /**
     * Sets the consumer related environment variables in the provided List.
     *
     * @param varList   List with environment variables
     */
    private void addConsumerEnvVars(List<EnvVar> varList) {
        if (consumer.getTls() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_CONSUMER, "true"));

            if (consumer.getTls().getTrustedCertificates() != null && consumer.getTls().getTrustedCertificates().size() > 0) {
                StringBuilder sb = new StringBuilder();
                boolean separator = false;
                for (CertSecretSource certSecretSource : consumer.getTls().getTrustedCertificates()) {
                    if (separator) {
                        sb.append(";");
                    }
                    sb.append(certSecretSource.getSecretName() + "/" + certSecretSource.getCertificate());
                    separator = true;
                }
                varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_CONSUMER, sb.toString()));
            }
        }

        AuthenticationUtils.configureClientAuthenticationEnvVars(consumer.getAuthentication(), varList, name -> ENV_VAR_PREFIX + name + "_CONSUMER");
    }

    /**
     * Sets the producer related environment variables in the provided List.
     *
     * @param varList   List with environment variables
     */
    private void addProducerEnvVars(List<EnvVar> varList) {
        if (producer.getTls() != null) {
            varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TLS_PRODUCER, "true"));

            if (producer.getTls().getTrustedCertificates() != null && producer.getTls().getTrustedCertificates().size() > 0) {
                StringBuilder sb = new StringBuilder();
                boolean separator = false;
                for (CertSecretSource certSecretSource : producer.getTls().getTrustedCertificates()) {
                    if (separator) {
                        sb.append(";");
                    }
                    sb.append(certSecretSource.getSecretName() + "/" + certSecretSource.getCertificate());
                    separator = true;
                }
                varList.add(buildEnvVar(ENV_VAR_KAFKA_MIRRORMAKER_TRUSTED_CERTS_PRODUCER, sb.toString()));
            }
        }

        AuthenticationUtils.configureClientAuthenticationEnvVars(producer.getAuthentication(), varList, name -> ENV_VAR_PREFIX + name + "_PRODUCER");
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
    protected String getDefaultLogConfigFileName() {
        return "mirrorMakerDefaultLoggingProperties";
    }

    protected String getInclude() {
        return include;
    }

    @Override
    protected String getServiceAccountName() {
        return KafkaMirrorMakerResources.serviceAccountName(cluster);
    }

    @Override
    protected boolean shouldPatchLoggerAppender() {
        return true;
    }
}

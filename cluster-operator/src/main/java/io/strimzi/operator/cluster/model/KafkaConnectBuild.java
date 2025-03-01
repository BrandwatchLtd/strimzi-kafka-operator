/*
 * Copyright Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.SecurityContext;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.openshift.api.model.BuildConfig;
import io.fabric8.openshift.api.model.BuildConfigBuilder;
import io.fabric8.openshift.api.model.BuildOutput;
import io.fabric8.openshift.api.model.BuildOutputBuilder;
import io.fabric8.openshift.api.model.BuildRequest;
import io.fabric8.openshift.api.model.BuildRequestBuilder;
import io.fabric8.openshift.api.model.DockerBuildStrategyBuilder;
import io.strimzi.api.kafka.model.ContainerEnvVar;
import io.strimzi.api.kafka.model.KafkaConnect;
import io.strimzi.api.kafka.model.KafkaConnectResources;
import io.strimzi.api.kafka.model.KafkaConnectSpec;
import io.strimzi.api.kafka.model.connect.build.Build;
import io.strimzi.api.kafka.model.connect.build.DockerOutput;
import io.strimzi.api.kafka.model.connect.build.ImageStreamOutput;
import io.strimzi.api.kafka.model.connect.build.Plugin;
import io.strimzi.api.kafka.model.template.KafkaConnectTemplate;
import io.strimzi.operator.cluster.ClusterOperatorConfig;
import io.strimzi.operator.cluster.model.securityprofiles.ContainerSecurityProviderContextImpl;
import io.strimzi.operator.cluster.model.securityprofiles.PodSecurityProviderContextImpl;
import io.strimzi.operator.common.Annotations;
import io.strimzi.operator.common.Reconciliation;
import io.strimzi.operator.common.Util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Model class for Kafka Connect Build - this model handled the build of the new image with custom connectors
 */
public class KafkaConnectBuild extends AbstractModel {
    protected static final String COMPONENT_TYPE = "kafka-connect-build";

    private static final String DEFAULT_KANIKO_EXECUTOR_IMAGE = "gcr.io/kaniko-project/executor:latest";

    protected static final String CO_ENV_VAR_CUSTOM_CONNECT_BUILD_POD_LABELS = "STRIMZI_CUSTOM_KAFKA_CONNECT_BUILD_LABELS";

    private Build build;
    private List<ContainerEnvVar> templateBuildContainerEnvVars;
    private SecurityContext templateBuildContainerSecurityContext;
    private Map<String, String> templateBuildConfigLabels;
    private Map<String, String> templateBuildConfigAnnotations;
    /*test*/ String baseImage;
    private List<String> additionalKanikoOptions;
    private String pullSecret;

    private static final Map<String, String> DEFAULT_POD_LABELS = new HashMap<>();
    static {
        String value = System.getenv(CO_ENV_VAR_CUSTOM_CONNECT_BUILD_POD_LABELS);
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
    protected KafkaConnectBuild(Reconciliation reconciliation, HasMetadata resource) {
        super(reconciliation, resource, KafkaConnectResources.buildPodName(resource.getMetadata().getName()), COMPONENT_TYPE);

        this.image = System.getenv().getOrDefault(ClusterOperatorConfig.STRIMZI_DEFAULT_KANIKO_EXECUTOR_IMAGE, DEFAULT_KANIKO_EXECUTOR_IMAGE);
    }

    /**
     * Created the KafkaConnectBuild instance from the Kafka Connect Custom Resource
     *
     * @param reconciliation The reconciliation
     * @param kafkaConnect  Kafka Connect CR with the build configuration
     * @param versions      Kafka versions configuration
     * @return              Instance of KafkaConnectBuild class
     */
    @SuppressWarnings({"checkstyle:CyclomaticComplexity", "checkstyle:NPathComplexity"})
    public static KafkaConnectBuild fromCrd(Reconciliation reconciliation, KafkaConnect kafkaConnect, KafkaVersion.Lookup versions) {
        KafkaConnectBuild build = new KafkaConnectBuild(reconciliation, kafkaConnect);
        KafkaConnectSpec spec = kafkaConnect.getSpec();

        if (spec == null) {
            throw new InvalidResourceException("Required .spec section is missing.");
        }

        if (spec.getBuild() != null)    {
            validateBuildConfiguration(spec.getBuild());

            // The additionalKanikoOptions are validated separately to avoid parsing the list twice
            if (spec.getBuild().getOutput() != null
                    && spec.getBuild().getOutput() instanceof DockerOutput dockerOutput) {
                if (dockerOutput.getAdditionalKanikoOptions() != null
                        && !dockerOutput.getAdditionalKanikoOptions().isEmpty())  {
                    validateAdditionalKanikoOptions(dockerOutput.getAdditionalKanikoOptions());
                    build.additionalKanikoOptions = dockerOutput.getAdditionalKanikoOptions();
                }
            }
        }

        build.baseImage = versions.kafkaConnectVersion(spec.getImage(), spec.getVersion());

        if (spec.getTemplate() != null) {
            KafkaConnectTemplate template = spec.getTemplate();

            ModelUtils.parsePodTemplate(build, template.getBuildPod());

            if (template.getBuildContainer() != null && template.getBuildContainer().getEnv() != null) {
                build.templateBuildContainerEnvVars = template.getBuildContainer().getEnv();
            }

            if (template.getBuildContainer() != null && template.getBuildContainer().getSecurityContext() != null) {
                build.templateBuildContainerSecurityContext = template.getBuildContainer().getSecurityContext();
            }

            if (template.getBuildConfig() != null) {
                build.pullSecret = template.getBuildConfig().getPullSecret();

                if (template.getBuildConfig().getMetadata() != null) {
                    if (template.getBuildConfig().getMetadata().getLabels() != null) {
                        build.templateBuildConfigLabels = template.getBuildConfig().getMetadata().getLabels();
                    }

                    if (template.getBuildConfig().getMetadata().getAnnotations() != null) {
                        build.templateBuildConfigAnnotations = template.getBuildConfig().getMetadata().getAnnotations();
                    }
                }
            }

            if (template.getBuildServiceAccount() != null && template.getBuildServiceAccount().getMetadata() != null) {
                build.templateServiceAccountLabels = template.getBuildServiceAccount().getMetadata().getLabels();
                build.templateServiceAccountAnnotations = template.getBuildServiceAccount().getMetadata().getAnnotations();
            }
        }
        build.templatePodLabels = Util.mergeLabelsOrAnnotations(build.templatePodLabels, DEFAULT_POD_LABELS);

        build.build = spec.getBuild();

        return build;
    }

    /**
     * Validates the Build configuration to check unique connector names etc.
     *
     * @param build     Kafka Connect Build configuration
     */
    private static void validateBuildConfiguration(Build build)    {
        if (build.getPlugins() == null) {
            throw new InvalidResourceException("List of connector plugins is required when Kafka Connect Build is used.");
        }

        List<String> names = build.getPlugins().stream().map(Plugin::getName).distinct().toList();

        if (names.size() != build.getPlugins().size())  {
            throw new InvalidResourceException("Connector plugins names have to be unique within a single KafkaConnect resource.");
        }

        for (Plugin plugin : build.getPlugins())    {
            if (plugin.getArtifacts() == null)  {
                throw new InvalidResourceException("Each connector plugin needs to have a list of artifacts.");
            }
        }
    }

    /**
     * Validates the additional Kaniko options configured by the user against the list of allowed options. If any
     * options which are not allowed are found, it raises an InvalidResourceException exception.
     *
     * @param desiredOptions    List of additional Kaniko options configured by the user
     */
    private static void validateAdditionalKanikoOptions(List<String> desiredOptions)    {
        List<String> allowedOptions = Arrays.asList(DockerOutput.ALLOWED_KANIKO_OPTIONS.split("\\s*,+\\s*"));
        List<String> forbiddenOptions = desiredOptions.stream()
                .map(option -> option.contains("=") ? option.substring(0, option.indexOf("=")) : option)
                .filter(option -> allowedOptions.stream().noneMatch(option::equals))
                .toList();

        if (!forbiddenOptions.isEmpty())    {
            throw new InvalidResourceException(".spec.build.additionalKanikoOptions contains forbidden options: " + forbiddenOptions);
        }
    }

    /**
     * Returns the build configuration of the KafkaConnect CR
     *
     * @return  Kafka Connect build configuration
     */
    public Build getBuild() {
        return build;
    }

    /**
     * Generates the name of the Service Account used by Kafka Connect Build
     *
     * @return  Name of the Kafka Connect Build service account for this cluster
     */
    @Override
    public String getServiceAccountName() {
        return KafkaConnectResources.buildServiceAccountName(cluster);
    }

    /**
     * Generates a ConfigMap with the Dockerfile used for the build
     *
     * @param dockerfile    Instance of the KafkaConnectDockerfile class with the prepared Dockerfile
     *
     * @return  ConfigMap with the Dockerfile
     */
    public ConfigMap generateDockerfileConfigMap(KafkaConnectDockerfile dockerfile)   {
        return createConfigMap(
                KafkaConnectResources.dockerFileConfigMapName(cluster),
                Collections.singletonMap("Dockerfile", dockerfile.getDockerfile())
        );
    }

    /**
     * Generates the Dockerfile based on the Kafka Connect build configuration.
     *
     * @return  Instance of the KafkaConnectDockerfile class with the prepared Dockerfile
     */
    public KafkaConnectDockerfile generateDockerfile()  {
        return new KafkaConnectDockerfile(baseImage, build);
    }

    /**
     * Generates builder Pod for building a new KafkaConnect container image with additional connector plugins
     *
     * @param isOpenShift       Flag defining whether we are running on OpenShift
     * @param imagePullPolicy   Image pull policy
     * @param imagePullSecrets  Image pull secrets
     * @param newBuildRevision  Revision of the build which will be build used for annotation
     *
     * @return  Pod which will build the new container image
     */
    public Pod generateBuilderPod(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets, String newBuildRevision) {
        return createPod(
                KafkaConnectResources.buildPodName(cluster),
                Collections.singletonMap(Annotations.STRIMZI_IO_CONNECT_BUILD_REVISION, newBuildRevision),
                getVolumes(isOpenShift),
                null,
                getContainers(imagePullPolicy),
                imagePullSecrets,
                securityProvider.kafkaConnectBuildPodSecurityContext(new PodSecurityProviderContextImpl(templateSecurityContext))
        );
    }

    /**
     * Generates a list of volumes used by the builder pod
     *
     * @param isOpenShift   Flag defining whether we are running on OpenShift
     *
     * @return  List of volumes
     */
    private List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumes = new ArrayList<>(2);

        volumes.add(VolumeUtils.createConfigMapVolume("dockerfile", KafkaConnectResources.dockerFileConfigMapName(cluster), Collections.singletonMap("Dockerfile", "Dockerfile")));

        if (build.getOutput() instanceof DockerOutput output) {

            if (output.getPushSecret() != null) {
                volumes.add(VolumeUtils.createSecretVolume("docker-credentials", output.getPushSecret(), Collections.singletonMap(".dockerconfigjson", "config.json"), isOpenShift));
            }
        } else {
            throw new RuntimeException("Kubernetes build requires output of type `docker`.");
        }

        return volumes;
    }

    /**
     * Generates a list of volume mounts used by the builder pod
     *
     * @return  List of volume mounts
     */
    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMounts = new ArrayList<>(2);

        volumeMounts.add(new VolumeMountBuilder().withName("dockerfile").withMountPath("/dockerfile").build());

        if (build.getOutput() instanceof DockerOutput output) {

            if (output.getPushSecret() != null) {
                volumeMounts.add(new VolumeMountBuilder().withName("docker-credentials").withMountPath("/kaniko/.docker").build());
            }
        } else {
            throw new RuntimeException("Kubernetes build requires output of type `docker`.");
        }

        return volumeMounts;
    }

    /**
     * Generates list of container environment variables for the builder pod. Currently contains only the proxy env vars
     * and any user defined vars from the templates.
     *
     * @return  List of environment variables
     */
    private List<EnvVar> getBuildContainerEnvVars() {
        // Add shared environment variables used for all containers
        List<EnvVar> varList = new ArrayList<>(getRequiredEnvVars());

        addContainerEnvsToExistingEnvs(varList, templateBuildContainerEnvVars);

        return varList;
    }

    /**
     * Generates the builder container with the Kaniko executor
     *
     * @param imagePullPolicy   Image pull policy
     *
     * @return  Builder container definition which will be used in the Pod
     */
    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {
        List<Container> containers = new ArrayList<>(1);

        List<String> args = additionalKanikoOptions != null ? new ArrayList<>(4 + additionalKanikoOptions.size()) : new ArrayList<>(4);
        args.add("--dockerfile=/dockerfile/Dockerfile");
        args.add("--image-name-with-digest-file=/dev/termination-log");
        args.add("--destination=" + build.getOutput().getImage());

        if (additionalKanikoOptions != null) {
            args.addAll(additionalKanikoOptions);
        }

        Container container = new ContainerBuilder()
                .withName(componentName)
                .withImage(getImage())
                .withArgs(args)
                .withVolumeMounts(getVolumeMounts())
                .withResources(build.getResources())
                .withSecurityContext(securityProvider.kafkaConnectBuildContainerSecurityContext(new ContainerSecurityProviderContextImpl(templateBuildContainerSecurityContext)))
                .withEnv(getBuildContainerEnvVars())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .build();

        containers.add(container);

        return containers;
    }

    /**
     * This method should return the name of the logging configuration file. But the Kaniko builder is not using any
     * logging configuration, so this currently just returns an unsupported exception (but it has to exist due to the
     * inheritance).
     *
     * @return  Name of the default logging configuration file
     */
    @Override
    protected String getDefaultLogConfigFileName() {
        throw new UnsupportedOperationException("Kafka Connect Build does not have any logging properties");
    }

    /**
     * Generates a BuildConfig which will be used to build new container images with additional connector plugins on OCP.
     *
     * @param dockerfile    Dockerfile which should be built by the BuildConfig
     *
     * @return  OpenShift BuildConfig for building new container images on OpenShift
     */
    public BuildConfig generateBuildConfig(KafkaConnectDockerfile dockerfile)    {
        BuildOutput output;

        if (build.getOutput() instanceof DockerOutput dockerOutput) {

            output = new BuildOutputBuilder()
                    .withNewTo()
                        .withKind("DockerImage")
                        .withName(dockerOutput.getImage())
                    .endTo()
                    .build();

            if (dockerOutput.getPushSecret() != null) {
                output.setPushSecret(new LocalObjectReferenceBuilder().withName(dockerOutput.getPushSecret()).build());
            }
        } else if (build.getOutput() instanceof ImageStreamOutput imageStreamOutput)  {

            output = new BuildOutputBuilder()
                    .withNewTo()
                        .withKind("ImageStreamTag")
                        .withName(imageStreamOutput.getImage())
                    .endTo()
                    .build();
        } else {
            throw new RuntimeException("Unknown output type " + build.getOutput().getType());
        }

        DockerBuildStrategyBuilder dockerBuildStrategyBuilder = new DockerBuildStrategyBuilder();
        if (pullSecret != null) {
            dockerBuildStrategyBuilder.withNewPullSecret().withName(pullSecret).endPullSecret();
        }

        return new BuildConfigBuilder()
                .withNewMetadata()
                    .withName(KafkaConnectResources.buildConfigName(cluster))
                    .withLabels(labels.withAdditionalLabels(templateBuildConfigLabels).toMap())
                    .withAnnotations(templateBuildConfigAnnotations)
                    .withNamespace(namespace)
                    .withOwnerReferences(ownerReference)
                .endMetadata()
                .withNewSpec()
                    .withOutput(output)
                    .withNewSource()
                        .withType("Dockerfile")
                        .withDockerfile(dockerfile.getDockerfile())
                    .endSource()
                    .withNewStrategy()
                        .withType("Docker")
                        .withDockerStrategy(dockerBuildStrategyBuilder.build())
                    .endStrategy()
                    .withResources(build.getResources())
                    .withRunPolicy("Serial")
                    .withFailedBuildsHistoryLimit(5)
                    .withSuccessfulBuildsHistoryLimit(5)
                    .withFailedBuildsHistoryLimit(5)
                .endSpec()
                .build();
    }

    /**
     * Generates OpenShift Build Request to start a new build using OpenShift Build feature
     *
     * @param buildRevision The revision of the build (to indicate if rebuild is needed)
     *
     * @return  The BuildRequest resource
     */
    public BuildRequest generateBuildRequest(String buildRevision)  {
        return new BuildRequestBuilder()
                .withNewMetadata()
                    .withName(KafkaConnectResources.buildConfigName(cluster))
                    .withNamespace(namespace)
                    .withAnnotations(Collections.singletonMap(Annotations.STRIMZI_IO_CONNECT_BUILD_REVISION, buildRevision))
                    .withLabels(labels.withAdditionalLabels(templateBuildConfigLabels).toMap())
                .endMetadata()
                .build();
    }
}

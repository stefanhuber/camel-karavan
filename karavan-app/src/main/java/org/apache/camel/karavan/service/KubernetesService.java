/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.karavan.service;

import io.fabric8.knative.internal.pkg.apis.Condition;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.DefaultKubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import io.fabric8.kubernetes.client.informers.SharedIndexInformer;
import io.fabric8.openshift.api.model.ImageStream;
import io.fabric8.openshift.client.OpenShiftClient;
import io.fabric8.tekton.client.DefaultTektonClient;
import io.fabric8.tekton.pipeline.v1beta1.*;
import io.quarkus.vertx.ConsumeEvent;
import io.vertx.mutiny.core.eventbus.EventBus;
import org.apache.camel.karavan.handler.*;
import org.apache.camel.karavan.model.Project;
import org.apache.camel.karavan.model.ProjectFile;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Default;
import javax.enterprise.inject.Produces;
import javax.inject.Inject;
import java.util.*;
import java.util.stream.Collectors;

import static org.apache.camel.karavan.service.ServiceUtil.APPLICATION_PROPERTIES_FILENAME;


@Default
@Readiness
@ApplicationScoped
public class KubernetesService implements HealthCheck{

    private static final Logger LOGGER = Logger.getLogger(KubernetesService.class.getName());
    public static final String START_INFORMERS = "start-informers";
    public static final String STOP_INFORMERS = "stop-informers";
    public static final int INFORMERS = 4;
    private static final String CAMEL_PREFIX = "camel";
    private static final String KARAVAN_PREFIX = "karavan";
    public static final String RUNNER_SUFFIX = "runner";
    private static final String JBANG_CACHE_SUFFIX = "jbang-cache";
    private static final String M2_CACHE_SUFFIX = "m2-cache";

    @Inject
    EventBus eventBus;

    @Inject
    InfinispanService infinispanService;

    @Produces
    public KubernetesClient kubernetesClient() {
        return new DefaultKubernetesClient();
    }

    @Produces
    public DefaultTektonClient tektonClient() {
        return new DefaultTektonClient(kubernetesClient());
    }

    @Produces
    public OpenShiftClient openshiftClient() {
        return kubernetesClient().adapt(OpenShiftClient.class);
    }

    @ConfigProperty(name = "kubernetes.namespace", defaultValue = KARAVAN_PREFIX)
    String currentNamespace;

    @ConfigProperty(name = "karavan.environment")
    public String environment;


    List<SharedIndexInformer> informers = new ArrayList<>(INFORMERS);

    @ConsumeEvent(value = START_INFORMERS, blocking = true)
    void startInformers(String data) {
        try {
            stopInformers(null);
            LOGGER.info("Starting Kubernetes Informers");

            SharedIndexInformer<Deployment> deploymentInformer = kubernetesClient().apps().deployments().inNamespace(getNamespace())
                    .withLabels(getRuntimeLabels()).inform();
            deploymentInformer.addEventHandlerWithResyncPeriod(new DeploymentEventHandler(infinispanService, this),30 * 1000L);
            informers.add(deploymentInformer);

            SharedIndexInformer<Service> serviceInformer = kubernetesClient().services().inNamespace(getNamespace())
                    .withLabels(getRuntimeLabels()).inform();
            serviceInformer.addEventHandlerWithResyncPeriod(new ServiceEventHandler(infinispanService, this),30 * 1000L);
            informers.add(serviceInformer);

            SharedIndexInformer<PipelineRun> pipelineRunInformer = tektonClient().v1beta1().pipelineRuns().inNamespace(getNamespace())
                    .withLabels(getRuntimeLabels()).inform();
            pipelineRunInformer.addEventHandlerWithResyncPeriod(new PipelineRunEventHandler(infinispanService, this),30 * 1000L);
            informers.add(pipelineRunInformer);

            SharedIndexInformer<Pod> podRunInformer = kubernetesClient().pods().inNamespace(getNamespace())
                    .withLabels(getRuntimeLabels()).inform();
            podRunInformer.addEventHandlerWithResyncPeriod(new PodEventHandler(infinispanService, this),30 * 1000L);
            informers.add(podRunInformer);

            LOGGER.info("Started Kubernetes Informers");
        } catch (Exception e) {
            LOGGER.error("Error starting informers: " + e.getMessage());
        }
    }

    
    @Override
    public HealthCheckResponse call() {
        if(informers.size() == INFORMERS) {
            return HealthCheckResponse.up("All Kubernetes informers are running.");
        }
        else {
            return HealthCheckResponse.down("kubernetes Informers are not running.");
        }
    }

    @ConsumeEvent(value = STOP_INFORMERS, blocking = true)
    void stopInformers(String data) {
        LOGGER.info("Stop Kubernetes Informers");
        informers.forEach(informer -> informer.close());
        informers.clear();
    }

    private String getPipelineName(Project project) {
        return "karavan-pipeline-" + environment + "-" + project.getRuntime();
    }

    public String createPipelineRun(Project project) {
        String pipeline = getPipelineName(project);
        LOGGER.info("Pipeline " + pipeline + " is creating for " + project.getProjectId());

        Map<String, String> labels = getRuntimeLabels(
                Map.of("karavan-project-id", project.getProjectId(),
                "tekton.dev/pipeline", pipeline)
        );

        ObjectMeta meta = new ObjectMetaBuilder()
                .withGenerateName(KARAVAN_PREFIX + "-" + project.getProjectId() + "-")
                .withLabels(labels)
                .withNamespace(getNamespace())
                .build();

        PipelineRef ref = new PipelineRefBuilder().withName(pipeline).build();

        PipelineRunSpec spec = new PipelineRunSpecBuilder()
                .withPipelineRef(ref)
                .withServiceAccountName("pipeline")
                .withParams(new ParamBuilder().withName("PROJECT_ID").withNewValue(project.getProjectId()).build())
                .withWorkspaces(
                        new WorkspaceBindingBuilder().withName(KARAVAN_PREFIX + "-" + M2_CACHE_SUFFIX)
                                .withNewPersistentVolumeClaim(KARAVAN_PREFIX + "-" + M2_CACHE_SUFFIX, false).build(),
                        new WorkspaceBindingBuilder().withName(KARAVAN_PREFIX + "-" + JBANG_CACHE_SUFFIX)
                                .withNewPersistentVolumeClaim(KARAVAN_PREFIX + "-" + JBANG_CACHE_SUFFIX, false).build())
                .build();

        PipelineRunBuilder pipelineRunBuilder = new PipelineRunBuilder()
                .withMetadata(meta)
                .withSpec(spec);

        PipelineRun pipelineRun = tektonClient().v1beta1().pipelineRuns().create(pipelineRunBuilder.build());
        LOGGER.info("Pipeline run started " + pipelineRun.getMetadata().getName());
        return pipelineRun.getMetadata().getName();
    }

    public PipelineRun getPipelineRun(String pipelineRuneName, String namespace) {
        return tektonClient().v1beta1().pipelineRuns().inNamespace(namespace).withName(pipelineRuneName).get();
    }

    public List<TaskRun> getTaskRuns(String pipelineRuneName, String namespace) {
        return tektonClient().v1beta1().taskRuns().inNamespace(namespace).withLabel("tekton.dev/pipelineRun", pipelineRuneName).list().getItems();
    }

    public String getContainerLog(String podName, String namespace) {
        String logText = kubernetesClient().pods().inNamespace(namespace).withName(podName).getLog(true);
        return logText;
    }

    public LogWatch getContainerLogWatch(String podName) {
        return kubernetesClient().pods().inNamespace(getNamespace()).withName(podName).tailingLines(100).watchLog();
    }

    public LogWatch getPipelineRunLogWatch(String pipelineRuneName) {
        List<TaskRun> tasks = getTaskRuns(pipelineRuneName, getNamespace());
        TaskRun taskRun = tasks.get(0);
        return kubernetesClient().pods().inNamespace(getNamespace()).withName(taskRun.getStatus().getPodName()).tailingLines(100).watchLog();
    }

    public String getPipelineRunLog(String pipelineRuneName, String namespace) {
        StringBuilder result = new StringBuilder();
        getTaskRuns(pipelineRuneName, namespace).forEach(taskRun -> {
            String podName = taskRun.getStatus().getPodName();
            taskRun.getStatus().getSteps().forEach(stepState -> {
                String logText = kubernetesClient().pods().inNamespace(namespace).withName(podName).inContainer(stepState.getContainer()).getLog(true);
                result.append(stepState.getContainer()).append(System.lineSeparator());
                result.append(logText).append(System.lineSeparator());
            });
        });
        return result.toString();
    }

    public PipelineRun getLastPipelineRun(String projectId, String pipelineName, String namespace) {
        return tektonClient().v1beta1().pipelineRuns().inNamespace(namespace)
                .withLabel("karavan-project-id", projectId)
                .withLabel("tekton.dev/pipeline", pipelineName)
                .list().getItems().stream().sorted((o1, o2) -> o2.getMetadata().getCreationTimestamp().compareTo(o1.getMetadata().getCreationTimestamp()))
                .findFirst().get();
    }

    public void stopPipelineRun(String pipelineRunName, String namespace) {
        try {
            LOGGER.info("Stop PipelineRun: " + pipelineRunName + " in the namespace: " + namespace);

            getTaskRuns(pipelineRunName, namespace).forEach(taskRun -> {
                taskRun.getStatus().setConditions(getCancelConditions("TaskRunCancelled"));
                kubernetesClient().pods().inNamespace(getNamespace()).withName(taskRun.getStatus().getPodName()).delete();
                tektonClient().v1beta1().taskRuns().inNamespace(namespace).resource(taskRun).replaceStatus();
            });

            PipelineRun run = tektonClient().v1beta1().pipelineRuns().inNamespace(namespace).withName(pipelineRunName).get();
            run.getStatus().setConditions(getCancelConditions("CancelledRunFinally"));
            tektonClient().v1beta1().pipelineRuns().inNamespace(namespace).resource(run).replaceStatus();
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
    }

    private List<Condition> getCancelConditions(String reason){
        List<Condition> cancelConditions = new ArrayList<>();
        Condition taskRunCancelCondition = new Condition();
        taskRunCancelCondition.setType("Succeeded");
        taskRunCancelCondition.setStatus("False");
        taskRunCancelCondition.setReason(reason);
        taskRunCancelCondition.setMessage("Cancelled successfully.");
        cancelConditions.add(taskRunCancelCondition);
        return cancelConditions;
    }
    
    public void rolloutDeployment(String name, String namespace) {
        try {
            kubernetesClient().apps().deployments().inNamespace(namespace).withName(name).rolling().restart();
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
    }

    public void deleteDeployment(String name, String namespace) {
        try {
            LOGGER.info("Delete deployment: " + name + " in the namespace: " + namespace);
            kubernetesClient().apps().deployments().inNamespace(namespace).withName(name).delete();
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
    }

    public void deletePod(String name, String namespace) {
        try {
            LOGGER.info("Delete pod: " + name + " in the namespace: " + namespace);
            kubernetesClient().pods().inNamespace(namespace).withName(name).delete();
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
    }

    public Deployment getDeployment(String name, String namespace) {
        try {
            return kubernetesClient().apps().deployments().inNamespace(namespace).withName(name).get();
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
            return null;
        }
    }

    public boolean hasDeployment(String name, String namespace) {
        try {
            Deployment deployment = kubernetesClient().apps().deployments().inNamespace(namespace).withName(name).get();
            return deployment != null;
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
            return false;
        }
    }

    public List<String> getCamelDeployments(String namespace) {
        try {
            return kubernetesClient().apps().deployments().inNamespace(namespace).withLabels(getRuntimeLabels()).list().getItems()
                    .stream().map(deployment -> deployment.getMetadata().getName()).collect(Collectors.toList());
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
            return List.of();
        }
    }

    private String getPodReason(Pod pod) {
        try {
            return pod.getStatus().getContainerStatuses().get(0).getState().getWaiting().getReason();
        } catch (Exception e) {
            return "";
        }
    }

    public List<String> getConfigMaps(String namespace) {
        List<String> result = new ArrayList<>();
        try {
        kubernetesClient().configMaps().inNamespace(namespace).list().getItems().forEach(configMap -> {
            String name = configMap.getMetadata().getName();
            if (configMap.getData() != null) {
                configMap.getData().keySet().forEach(data -> result.add(name + "/" + data));
            }
        });
        } catch (Exception e) {
            LOGGER.error(e);
        }
        return result;
    }

    public List<String> getSecrets(String namespace) {
        List<String> result = new ArrayList<>();
        try {
            kubernetesClient().secrets().inNamespace(namespace).list().getItems().forEach(secret -> {
                String name = secret.getMetadata().getName();
                if (secret.getData() != null) {
                    secret.getData().keySet().forEach(data -> result.add(name + "/" + data));
                }
            });
        } catch (Exception e) {
            LOGGER.error(e);
        }
        return result;
    }

    public List<String> getServices(String namespace) {
        List<String> result = new ArrayList<>();
        try {
        kubernetesClient().services().inNamespace(namespace).list().getItems().forEach(service -> {
            String name = service.getMetadata().getName();
            String host = name + "." + namespace + ".svc.cluster.local";
            service.getSpec().getPorts().forEach(port -> result.add(name + "|" + host + ":" + port.getPort()));
        });
        } catch (Exception e) {
            LOGGER.error(e);
        }
        return result;
    }

    public List<String> getProjectImageTags(String projectId, String namespace) {
        List<String> result = new ArrayList<>();
        try {
            if (isOpenshift()) {
                ImageStream is = openshiftClient().imageStreams().inNamespace(namespace).withName(projectId).get();
                if (is != null) {
                    result.addAll(is.getSpec().getTags().stream().map(t -> t.getName()).sorted(Comparator.reverseOrder()).collect(Collectors.toList()));
                }
            } else {
                // TODO: Implement for Kubernetes/Minikube
            }
        } catch (Exception ex) {
            LOGGER.error(ex.getMessage());
        }
        return result;
    }

    public String tryCreatePod(Project project) {
        String name = project.getProjectId() + "-" + RUNNER_SUFFIX;
        createPVC(name + "-" + JBANG_CACHE_SUFFIX);
        createPVC(name + "-" + M2_CACHE_SUFFIX);
        Pod old = kubernetesClient().pods().inNamespace(getNamespace()).withName(name).get();
        if (old == null) {
            ProjectFile properties = infinispanService.getProjectFile(project.getProjectId(), APPLICATION_PROPERTIES_FILENAME);
            Map<String,String> containerResources = ServiceUtil
                    .getRunnerContainerResourcesMap(properties, isOpenshift(), project.getRuntime().equals("quarkus"));
            System.out.println(containerResources);
            Pod pod = getPod(project.getProjectId(), name, containerResources);
            Pod result = kubernetesClient().resource(pod).create();
            LOGGER.info("Created pod " + result.getMetadata().getName());
        }
        return name;
    }

    public ResourceRequirements getResourceRequirements(Map<String,String> containerResources) {
        return new ResourceRequirementsBuilder()
                .addToRequests("cpu", new Quantity(containerResources.get("requests.cpu")))
                .addToRequests("memory", new Quantity(containerResources.get("requests.memory")))
                .addToLimits("cpu", new Quantity(containerResources.get("limits.cpu")))
                .addToLimits("memory", new Quantity(containerResources.get("limits.memory")))
                .build();
    }

    private Pod getPod(String projectId, String name, Map<String,String> containerResources) {
        Map<String,String> labels = new HashMap<>();
        labels.putAll(getRuntimeLabels());
        labels.putAll(getKaravanTypeLabel());
        labels.put("project", projectId);

        ResourceRequirements resources = getResourceRequirements(containerResources);

        ObjectMeta meta = new ObjectMetaBuilder()
                .withName(name)
                .withLabels(labels)
                .withNamespace(getNamespace())
                .build();

        ContainerPort port = new ContainerPortBuilder()
                .withContainerPort(8080)
                .withName("http")
                .withProtocol("TCP")
                .build();

        Container container = new ContainerBuilder()
                .withName(name)
                .withImage("entropy1/camel-karavan-runner")
//                .withImage("ghcr.io/apache/camel-karavan-runner:3.20.2-snapshot")
                .withPorts(port)
                .withResources(resources)
                .withVolumeMounts(new VolumeMountBuilder().withName(name + "-" + JBANG_CACHE_SUFFIX).withMountPath("/root/.m2").build())
                .withVolumeMounts(new VolumeMountBuilder().withName(name + "-" + M2_CACHE_SUFFIX).withMountPath("/jbang/.jbang/cache").build())
                .build();

        PodSpec spec = new PodSpecBuilder()
                .withTerminationGracePeriodSeconds(0L)
                .withContainers(container)
                .withVolumes(new VolumeBuilder().withName(name + "-" + JBANG_CACHE_SUFFIX)
                        .withNewPersistentVolumeClaim(name + "-" + JBANG_CACHE_SUFFIX, false).build())
                .withVolumes(new VolumeBuilder().withName(name + "-" + M2_CACHE_SUFFIX)
                        .withNewPersistentVolumeClaim(name + "-" + M2_CACHE_SUFFIX, false).build())
                .build();

        return new PodBuilder()
                .withMetadata(meta)
                .withSpec(spec)
                .build();
    }

    private void createPVC(String pvcName) {
        PersistentVolumeClaim old = kubernetesClient().persistentVolumeClaims().inNamespace(getNamespace()).withName(pvcName).get();
        if (old == null) {
            PersistentVolumeClaim pvc = new PersistentVolumeClaimBuilder()
                    .withNewMetadata()
                    .withName(pvcName)
                    .withNamespace(getNamespace())
                    .withLabels(getKaravanTypeLabel())
                    .endMetadata()
                    .withNewSpec()
                    .withResources(new ResourceRequirementsBuilder().withRequests(Map.of("storage", new Quantity("2Gi"))).build())
                    .withVolumeMode("Filesystem")
                    .withAccessModes("ReadWriteOnce")
                    .endSpec()
                    .build();
            kubernetesClient().resource(pvc).create();
        }
    }

    public Secret getKaravanSecret() {
        return kubernetesClient().secrets().inNamespace(getNamespace()).withName("karavan").get();
    }

    public Map<String, String> getRuntimeLabels() {
        Map<String, String> result = new HashMap<>();
        result.put(getRuntimeLabelName(), CAMEL_PREFIX);
        return result;
    }

    public String getRuntimeLabelName() {
        return isOpenshift() ? "app.openshift.io/runtime" : "app.kubernetes.io/runtime";
    }

    public Map<String, String> getRuntimeLabels(Map<String, String> add) {
        Map<String, String> map = getRuntimeLabels();
        map.putAll(add);
        return map;
    }

    public static Map<String, String> getKaravanTypeLabel() {
        return Map.of("karavan/type" , "runner");
    }

    public boolean isOpenshift() {
        return inKubernetes() ? kubernetesClient().isAdaptable(OpenShiftClient.class) : false;
    }

    public String getCluster() {
        return kubernetesClient().getMasterUrl().getHost();
    }
    public String getNamespace() {
        return currentNamespace;
    }

    public boolean inKubernetes() {
        return !Objects.equals(getNamespace(), "localhost");
    }

}

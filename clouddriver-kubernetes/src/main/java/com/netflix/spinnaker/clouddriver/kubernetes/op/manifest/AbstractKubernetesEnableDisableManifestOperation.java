/*
 * Copyright 2018 Google, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.netflix.spinnaker.clouddriver.kubernetes.op.manifest;

import com.netflix.spinnaker.clouddriver.data.task.Task;
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository;
import com.netflix.spinnaker.clouddriver.kubernetes.description.JsonPatch;
import com.netflix.spinnaker.clouddriver.kubernetes.description.KubernetesCoordinates;
import com.netflix.spinnaker.clouddriver.kubernetes.description.KubernetesPatchOptions;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesEnableDisableManifestDescription;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesKind;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesManifest;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesManifestAnnotater;
import com.netflix.spinnaker.clouddriver.kubernetes.description.manifest.KubernetesManifestTraffic;
import com.netflix.spinnaker.clouddriver.kubernetes.op.OperationResult;
import com.netflix.spinnaker.clouddriver.kubernetes.op.handler.CanLoadBalance;
import com.netflix.spinnaker.clouddriver.kubernetes.op.handler.HasPods;
import com.netflix.spinnaker.clouddriver.kubernetes.security.KubernetesV2Credentials;
import com.netflix.spinnaker.clouddriver.orchestration.AtomicOperation;
import java.util.List;
import org.apache.commons.lang.WordUtils;
import org.apache.commons.lang3.tuple.Pair;

public abstract class AbstractKubernetesEnableDisableManifestOperation
    implements AtomicOperation<OperationResult> {
  private final KubernetesEnableDisableManifestDescription description;
  private final KubernetesV2Credentials credentials;
  private final String OP_NAME = getVerbName().toUpperCase() + "_MANIFEST";

  protected abstract String getVerbName();

  protected abstract List<JsonPatch> patchResource(
      CanLoadBalance loadBalancerHandler,
      KubernetesManifest loadBalancer,
      KubernetesManifest target);

  protected AbstractKubernetesEnableDisableManifestOperation(
      KubernetesEnableDisableManifestDescription description) {
    this.description = description;
    this.credentials = description.getCredentials().getCredentials();
  }

  private static Task getTask() {
    return TaskRepository.threadLocalTask.get();
  }

  private List<String> determineLoadBalancers(KubernetesManifest target) {
    getTask().updateStatus(OP_NAME, "Getting load balancer list to " + getVerbName() + "...");
    List<String> result = description.getLoadBalancers();
    if (result != null && !result.isEmpty()) {
      getTask().updateStatus(OP_NAME, "Using supplied list [" + String.join(", ", result) + "]");
    } else {
      KubernetesManifestTraffic traffic = KubernetesManifestAnnotater.getTraffic(target);
      result = traffic.getLoadBalancers();
      getTask().updateStatus(OP_NAME, "Using annotated list [" + String.join(", ", result) + "]");
    }

    return result;
  }

  private void op(String loadBalancerName, KubernetesManifest target) {
    Pair<KubernetesKind, String> name;
    try {
      name = KubernetesManifest.fromFullResourceName(loadBalancerName);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(
          String.format(
              "Failed to perform operation with load balancer '%s'. Load balancers must be specified in the form '{kind} {name}', e.g. 'service my-service'",
              loadBalancerName),
          e);
    }

    CanLoadBalance loadBalancerHandler =
        CanLoadBalance.lookupProperties(credentials.getResourcePropertyRegistry(), name);
    KubernetesManifest loadBalancer =
        credentials.get(name.getLeft(), target.getNamespace(), name.getRight());

    List<JsonPatch> patch = patchResource(loadBalancerHandler, loadBalancer, target);

    getTask().updateStatus(OP_NAME, "Patching target for '" + loadBalancerName + '"');
    credentials.patch(
        target.getKind(),
        target.getNamespace(),
        target.getName(),
        KubernetesPatchOptions.json(),
        patch);

    HasPods podHandler = null;
    try {
      podHandler =
          HasPods.lookupProperties(credentials.getResourcePropertyRegistry(), target.getKind());
    } catch (IllegalArgumentException e) {
      // this is OK, the workload might not have pods
    }

    if (podHandler != null) {
      getTask().updateStatus(OP_NAME, "Patching pods for '" + loadBalancerName + '"');
      List<KubernetesManifest> pods = podHandler.pods(credentials, target);
      // todo(lwander) parallelize, this will get slow for large workloads
      for (KubernetesManifest pod : pods) {
        patch = patchResource(loadBalancerHandler, loadBalancer, pod);
        credentials.patch(
            pod.getKind(), pod.getNamespace(), pod.getName(), KubernetesPatchOptions.json(), patch);
      }
    }
  }

  @Override
  public OperationResult operate(List priorOutputs) {
    getTask().updateStatus(OP_NAME, "Starting " + getVerbName() + " operation...");
    KubernetesCoordinates coordinates = description.getPointCoordinates();
    KubernetesManifest target =
        credentials.get(coordinates.getKind(), coordinates.getNamespace(), coordinates.getName());
    determineLoadBalancers(target).forEach(l -> op(l, target));

    getTask()
        .updateStatus(
            OP_NAME,
            WordUtils.capitalize(getVerbName()) + " operation for " + coordinates + " succeeded");
    return null;
  }
}

<template>
  <div>User Policy</div>
  <div ref="container" class="w-100% h-500px"></div>
</template>

<script setup lang="ts">
import { IG6GraphEvent, TreeGraph } from "@antv/g6";
import { ContactApi } from "@/api";
import { Policy } from "@/interface";
import { initGraph } from "@/views/config/policy/graph";

defineOptions({
  name: "UserPolicy",
});
const route = useRoute();
const contact = route.query.id as string;
const policyId = route.query.policy as string;
const container = ref<HTMLDivElement>();
const policy = ref<Policy>();
let destroyGraphHandler: () => void;
let graph: TreeGraph;
const mockData = {
  id: "policy.admin",
  name: "管理员权限",
  effect: "ALLOW",
  type: "policy-root",
  children: [
    {
      id: "policy.admin.1",
      groupType: "ALL",
      type: "policy-node",
      parent: "policy.admin",
      children: [
        {
          id: "policy.admin.1.c1.1",
          type: "policy-rule",
          oType: "Subject",
          operator: "Contains",
          key: "role",
          value: "role.admin",
          parent: "policy.admin.1",
        },
        {
          id: "policy.admin.1.c1.2",
          type: "policy-rule",
          oType: "Resource",
          operator: "IsChild",
          key: "id",
          value: "*",
          parent: "policy.admin.1",
        },
        {
          id: "policy.admin.3",
          groupType: "Any",
          type: "policy-node",
          parent: "policy.admin.1",
          children: [
            {
              id: "policy.admin.3.c1.1",
              type: "policy-rule",
              oType: "Subject",
              operator: "Contains",
              key: "role",
              value: "role.admin",
              parent: "policy.admin.3",
            },
            {
              id: "policy.admin.3.c1.2",
              type: "policy-rule",
              oType: "Resource",
              operator: "IsChild",
              key: "id",
              value: "*",
              parent: "policy.admin.3",
            },
          ],
        },
      ],
    },
    {
      id: "policy.admin.2",
      groupType: "ALL",
      type: "policy-node",
      parent: "policy.admin",
      children: [
        {
          id: "policy.admin.2.c1.1",
          type: "policy-rule",
          oType: "Subject",
          operator: "Contains",
          key: "role",
          value: "role.admin",
          parent: "policy.admin.2",
        },
        {
          id: "policy.admin.2.c1.2",
          type: "policy-rule",
          oType: "Resource",
          operator: "IsChild",
          key: "id",
          value: "*",
          parent: "policy.admin.2",
        },
      ],
    },
  ],
};
onMounted(() => {
  if (contact && policyId) {
    ContactApi.fetchContactPolicy(contact, policyId).then((data) => {
      policy.value = data;
    });
  }
  const { graph: g, destroy } = initGraph(container.value!, mockData);
  g.on("edit-text:click", (e: IG6GraphEvent) => {
    const { target } = e;
    const id = target.get("modelId");
    const item = graph.findById(id);
    const nodeModel = item.getModel();
    console.log(nodeModel.type);
    console.log(nodeModel.parent);
  });
  graph = g;
  destroyGraphHandler = destroy;
});
onUnmounted(() => {
  destroyGraphHandler();
});
</script>

<style lang="scss" scoped></style>

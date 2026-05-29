<template>
  <div class="policy-flow-node" :class="statusClass">
    <Handle type="target" :position="Position.Left" />
    <div class="node-title">{{ snapshot.groupType }}</div>

    <span class="node-action node-action-edit nodrag" @click.stop="actions.edit(props.id)">E</span>
    <span class="node-action node-action-append nodrag" @click.stop="actions.append(props.id)">+</span>
    <span class="node-action node-action-remove nodrag" @click.stop="actions.remove(props.id)">-</span>
    <span v-if="props.data.hasChildren" class="collapse-action nodrag" @click.stop="actions.toggleCollapse(props.id)">
      {{ props.data.collapsed ? "+" : "-" }}
    </span>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
import { computed, inject } from "vue";
import { Handle, Position, type NodeProps } from "@vue-flow/core";
import type { GraphFlowData, NodeSnapshot } from "@/views/config/policy/model";
import { POLICY_NODE_ACTIONS, noopPolicyNodeActions } from "@/views/config/policy/nodeActions";

const props = defineProps<NodeProps<GraphFlowData>>();
const actions = inject(POLICY_NODE_ACTIONS, noopPolicyNodeActions);

const snapshot = computed(() => props.data.snapshot as NodeSnapshot);
const statusClass = computed(() => (props.data.status ? `is-${props.data.status}` : ""));
</script>

<style scoped lang="scss">
@import "./node-base.scss";

.node-title {
  position: absolute;
  inset: 0 36px 0 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 20px;
  text-align: center;
}
</style>

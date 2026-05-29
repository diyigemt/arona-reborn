<template>
  <div class="policy-flow-node" :class="statusClass">
    <Handle type="target" :position="Position.Left" />
    <ElTooltip :content="snapshot.name" placement="top" :show-after="300">
      <div class="root-name">{{ displayName }}</div>
    </ElTooltip>
    <div class="root-effect">{{ snapshot.effect }}</div>

    <span class="node-action node-action-edit nodrag" @click.stop="actions.edit(props.id)">E</span>
    <span class="node-action node-action-append nodrag" @click.stop="actions.append(props.id)">+</span>
    <span v-if="props.data.hasChildren" class="collapse-action nodrag" @click.stop="actions.toggleCollapse(props.id)">
      {{ props.data.collapsed ? "+" : "-" }}
    </span>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
import { computed, inject } from "vue";
import { Handle, Position, type NodeProps } from "@vue-flow/core";
import type { GraphFlowData, RootSnapshot } from "@/views/config/policy/model";
import { POLICY_NODE_ACTIONS, noopPolicyNodeActions } from "@/views/config/policy/nodeActions";

const props = defineProps<NodeProps<GraphFlowData>>();
const actions = inject(POLICY_NODE_ACTIONS, noopPolicyNodeActions);

const snapshot = computed(() => props.data.snapshot as RootSnapshot);
// 复刻旧实现：名称超 28 字截断省略。
const displayName = computed(() => {
  const name = snapshot.value.name || "";
  return name.length > 28 ? `${name.substring(0, 28)}...` : name;
});
const statusClass = computed(() => (props.data.status ? `is-${props.data.status}` : ""));
</script>

<style scoped lang="scss">
@import "./node-base.scss";

.root-name {
  position: absolute;
  top: 11px;
  left: 12px;
  max-width: 150px;
  overflow: hidden;
  cursor: pointer;
  white-space: nowrap;
  text-overflow: ellipsis;
  opacity: 0.85;
}

.root-effect {
  position: absolute;
  bottom: 8px;
  left: 12px;
  opacity: 0.85;
}
</style>

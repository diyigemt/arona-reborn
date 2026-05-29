<template>
  <div class="policy-flow-node" :class="statusClass">
    <Handle type="target" :position="Position.Left" />
    <ElTooltip :content="snapshot.name" placement="top" :show-after="300">
      <div class="rule-title">{{ snapshot.name }}</div>
    </ElTooltip>

    <span class="node-action node-action-edit nodrag" @click.stop="actions.edit(props.id)">E</span>
    <span class="node-action node-action-append nodrag" @click.stop="actions.append(props.id)">+</span>
    <span class="node-action node-action-remove nodrag" @click.stop="actions.remove(props.id)">-</span>
    <Handle type="source" :position="Position.Right" />
  </div>
</template>

<script setup lang="ts">
import { computed, inject } from "vue";
import { Handle, Position, type NodeProps } from "@vue-flow/core";
import type { GraphFlowData, RuleSnapshot } from "@/views/config/policy/model";
import { POLICY_NODE_ACTIONS, noopPolicyNodeActions } from "@/views/config/policy/nodeActions";

const props = defineProps<NodeProps<GraphFlowData>>();
const actions = inject(POLICY_NODE_ACTIONS, noopPolicyNodeActions);

const snapshot = computed(() => props.data.snapshot as RuleSnapshot);
const statusClass = computed(() => (props.data.status ? `is-${props.data.status}` : ""));
</script>

<style scoped lang="scss">
@import "./node-base.scss";

.rule-title {
  position: absolute;
  inset: 0 36px 0 12px;
  display: flex;
  align-items: center;
  justify-content: center;
  overflow: hidden;
  cursor: pointer;
  font-size: 12px;
  line-height: 16px;
  text-align: center;
  // ruleDisplayName 含 \n，保留换行。
  white-space: pre-line;
}
</style>

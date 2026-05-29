import { ref } from "vue";
import type { Edge } from "@vue-flow/core";
import {
  addChildNode,
  removeNode,
  treeToFlow,
  updateNodeData,
  type GraphRoot,
  type PolicyFlowNode,
  type PolicyNodeStatus,
  type UpdateNodePatch,
} from "@/views/config/policy/model";
import { layoutWithDagre } from "@/views/config/policy/layout";

// addChildNode 第三参的 payload 联合类型（model 未单独导出，这里按签名取用）。
type AddChildPayload = Parameters<typeof addChildNode>[2];

function createEmptyRoot(): GraphRoot {
  return { id: "", kind: "policy-root", parent: "", name: "", effect: "ALLOW", children: [] };
}

/**
 * 策略树 ↔ Vue Flow 视图的编排组合式函数。
 *
 * graphData（嵌套树）是唯一可写真源，onSave 经 getData()→mapGraphToPolicy 读它；
 * nodes/edges 始终由 treeToFlow + dagre 从 graphData 派生（rebuild），组件只读。
 * 任何结构/数据突变都先清除测试状态（status 不跨编辑保留），再 rebuild。
 */
export function usePolicyGraph() {
  let graphData: GraphRoot = createEmptyRoot();
  const collapsed = new Set<string>();
  let statusById = new Map<string, PolicyNodeStatus>();
  const nodes = ref<PolicyFlowNode[]>([]);
  const edges = ref<Edge[]>([]);

  function rebuild(): void {
    const flow = treeToFlow(graphData, { collapsed, statusById });
    nodes.value = layoutWithDagre(flow.nodes, flow.edges);
    edges.value = flow.edges;
  }

  function clearStatus(): void {
    if (statusById.size) statusById = new Map();
  }

  /** 切换 / 新建 / 导入策略：重置树、折叠与测试状态。 */
  function setData(root: GraphRoot): void {
    graphData = root;
    collapsed.clear();
    clearStatus();
    rebuild();
  }

  /** 供 onSave / onDelete / doTest 读取当前最新树。 */
  function getData(): GraphRoot {
    return graphData;
  }

  function addChild(parentId: string, payload: AddChildPayload): void {
    addChildNode(graphData, parentId, payload);
    clearStatus();
    rebuild();
  }

  function update(id: string, patch: UpdateNodePatch): void {
    updateNodeData(graphData, id, patch);
    clearStatus();
    rebuild();
  }

  function remove(id: string): void {
    removeNode(graphData, id, collapsed);
    clearStatus();
    rebuild();
  }

  /** 测试结果上色（当前仅根节点）。不清状态——这本身就是要展示的状态。 */
  function setStatus(id: string, status: PolicyNodeStatus): void {
    statusById = new Map(statusById).set(id, status);
    rebuild();
  }

  function toggleCollapse(id: string): void {
    if (collapsed.has(id)) {
      collapsed.delete(id);
    } else {
      collapsed.add(id);
    }
    rebuild();
  }

  return { nodes, edges, rebuild, setData, getData, addChild, update, remove, setStatus, toggleCollapse };
}

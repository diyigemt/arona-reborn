import { graphlib, layout } from "@dagrejs/dagre";
import type { Edge, Node } from "@vue-flow/core";

// 策略节点固定尺寸（与节点组件 CSS 必须一致），dagre 据此布局，不依赖 Vue Flow 的运行期测量。
export const NODE_W = 202;
export const NODE_H = 60;

// 树形：从左到右分层。ranksep 取 ~indent(300) 接近旧 indented 观感；nodesep 控制同层间距。
const RANK_SEP = 300;
const NODE_SEP = 40;

/**
 * 用 dagre 计算 LR 分层布局并回写每个节点的 position。
 * 不 mutate 入参，返回带新 position 的 nodes 副本。
 */
export function layoutWithDagre<T = unknown>(nodes: Node<T>[], edges: Edge[]): Node<T>[] {
  const g = new graphlib.Graph();
  g.setGraph({ rankdir: "LR", ranksep: RANK_SEP, nodesep: NODE_SEP });
  g.setDefaultEdgeLabel(() => ({}));

  nodes.forEach((node) => g.setNode(node.id, { width: NODE_W, height: NODE_H }));
  edges.forEach((edge) => g.setEdge(edge.source, edge.target));

  layout(g);

  return nodes.map((node) => {
    const pos = g.node(node.id);
    // dagre 返回节点中心点，Vue Flow 的 position 以左上角为基准 → 减去半宽/半高。
    return {
      ...node,
      position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 },
    };
  });
}

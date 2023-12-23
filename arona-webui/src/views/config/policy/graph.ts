// @ts-nocheck
import { Ref, watch } from "vue";
import G6, { IG6GraphEvent, IGroup, TreeGraph } from "@antv/g6";

// eslint-disable-next-line import/prefer-default-export
export function initGraph(container: HTMLDivElement, data: unknown) {
  const { width, height } = useElementBounding(container);
  const tooltip = new G6.Tooltip({
    offsetX: 20,
    offsetY: 30,
    itemTypes: ["node"],
    getContent: (e) => {
      if (!e || !e.item) return "";
      const outDiv = document.createElement("div");
      const nodeName = e.item.getModel().name || e.item.getModel().formater;
      if (!nodeName) return "";
      if (typeof nodeName === "function") {
        return nodeName();
      }
      return nodeName.toString();
    },
    shouldBegin: (e) => {
      if (!e) return false;
      const name = e.target.get("name");
      return ["name-shape", "mask-label-shape", "rule-name-item"].includes(name);
    },
  });
  const graph = new G6.TreeGraph({
    container,
    width: width.value,
    height: height.value,
    ...defaultConfig,
    plugins: [tooltip],
  });
  registerNodes(graph);
  graph.data(data);
  graph.render();

  function handleCollapse(e: IG6GraphEvent) {
    const { target } = e;
    const id = target.get("modelId");
    const item = graph.findById(id);
    const nodeModel = item.getModel();
    nodeModel.collapsed = !nodeModel.collapsed;
    graph.layout();
    graph.setItemState(item, "collapse", nodeModel.collapsed as boolean);
  }
  graph.on("collapse-text:click", (e) => {
    handleCollapse(e);
  });
  graph.on("collapse-back:click", (e) => {
    handleCollapse(e);
  });

  // 监听画布缩放，缩小到一定程度，节点显示缩略样式
  let currentLevel = 1;
  const briefZoomThreshold = Math.max(graph.getZoom(), 0.5);
  graph.on("viewportchange", (e) => {
    if (e.action !== "zoom") return;
    const currentZoom = graph.getZoom();
    let toLevel = currentLevel;
    if (currentZoom < briefZoomThreshold) {
      toLevel = 0;
    } else {
      toLevel = 1;
    }
    if (toLevel !== currentLevel) {
      currentLevel = toLevel;
      graph.getNodes().forEach((node) => {
        graph.updateItem(node, {
          level: toLevel,
        });
      });
    }
  });
  const effectHandle = watch(
    () => [width.value, height.value],
    (cur) => {
      graph.changeSize(cur[0], cur[1]);
    },
  );
  return {
    graph,
    destroy() {
      effectHandle();
      graph.destroy();
    },
  };
}

// 默认配置
const defaultConfig = {
  modes: {
    default: ["zoom-canvas", "drag-canvas"],
  },
  fitView: true,
  fitViewPadding: [20, 50],
  animate: true,
  defaultNode: {
    type: "policy-root",
  },
  defaultEdge: {
    type: "cubic-horizontal",
    style: {
      stroke: "#a0cfff",
    },
  },
  layout: {
    type: "indented",
    direction: "LR",
    dropCap: false,
    indent: 300,
    getHeight: () => {
      return 60;
    },
  },
};
const rectConfig = {
  width: 202,
  height: 60,
  lineWidth: 1,
  fontSize: 12,
  fill: "#ecf5ff",
  radius: 4,
  stroke: "#a0cfff",
  opacity: 1,
};

const nodeOrigin = {
  x: -rectConfig.width / 2,
  y: -rectConfig.height / 2,
};
export const colors = {
  Permit: "#67c23a",
  Reject: "#f56c6c",
  Normal: "#409eff",
};
const textConfig = {
  textAlign: "left",
  textBaseline: "bottom",
  fill: colors.Normal,
};
function addCollapse(group: IGroup, id: string, collapsed: boolean) {
  group.addShape("rect", {
    attrs: {
      x: rectConfig.width / 2 - 8,
      y: -8,
      width: 16,
      height: 16,
      stroke: "#a0cfff",
      cursor: "pointer",
      fill: "#fff",
    },
    name: "collapse-back",
    modelId: id,
  });

  // collpase text
  group.addShape("text", {
    attrs: {
      x: rectConfig.width / 2,
      y: 0,
      textAlign: "center",
      textBaseline: "middle",
      text: collapsed ? "+" : "-",
      fontSize: 16,
      cursor: "pointer",
      fill: "#a0cfff",
    },

    name: "collapse-text",
    modelId: id,
  });
}
function addEdit(group: IGroup, id: string) {
  // collpase text
  group.addShape("text", {
    attrs: {
      x: rectConfig.width / 2 - 16,
      y: -rectConfig.height / 2 + 14,
      textAlign: "center",
      textBaseline: "middle",
      text: "E",
      fontSize: 16,
      cursor: "pointer",
      fill: colors.Normal,
    },

    name: "edit-text",
    modelId: id,
  });
}
function addChild(group: IGroup, id: string) {
  // collpase text
  group.addShape("text", {
    attrs: {
      x: rectConfig.width / 2 - 32,
      y: -rectConfig.height / 2 + 13,
      textAlign: "center",
      textBaseline: "middle",
      text: "+",
      fontSize: 20,
      cursor: "pointer",
      fill: colors.Normal,
    },

    name: "append-child",
    modelId: id,
  });
}
function addRemove(group: IGroup, id: string) {
  // collpase text
  group.addShape("text", {
    attrs: {
      x: rectConfig.width / 2 - 16,
      y: rectConfig.height / 2 - 8,
      textAlign: "center",
      textBaseline: "middle",
      text: "-",
      fontSize: 24,
      cursor: "pointer",
      fill: colors.Reject,
    },

    name: "remove-self",
    modelId: id,
  });
}
function registerNodes(graph: TreeGraph) {
  G6.registerNode(
    "policy-root",
    {
      shapeType: "policy-root",
      draw(config, group) {
        const { name, effect, collapsed } = config;

        const rect = group.addShape("rect", {
          attrs: {
            x: nodeOrigin.x,
            y: nodeOrigin.y,
            ...rectConfig,
          },
          name: "root-shape",
        });

        const rectBBox = rect.getBBox();
        // label title
        group.addShape("text", {
          attrs: {
            ...textConfig,
            x: 12 + nodeOrigin.x,
            y: 20 + nodeOrigin.y,
            text: name.length > 28 ? `${name.substring(0, 28)}...` : name,
            fontSize: 12,
            opacity: 0.85,
            cursor: "pointer",
          },

          name: "name-shape",
        });
        group.addShape("text", {
          attrs: {
            ...textConfig,
            x: 12 + nodeOrigin.x,
            y: 50 + nodeOrigin.y,
            text: effect,
            fontSize: 12,
            opacity: 0.85,
          },
          name: "effect",
        });
        addEdit(group, config.id);
        addChild(group, config.id);
        // collapse rect
        if (config.children) {
          addCollapse(group, config.id, collapsed);
        }

        this.drawLinkPoints(config, group);
        return rect;
      },
      // eslint-disable-next-line consistent-return
      update(config, item) {
        const { level, form, effect, name } = config;
        const group = item.getContainer();
        let maskLabel = group.find((ele) => ele.get("name") === "mask-label-shape");
        if (level === 0) {
          group.get("children").forEach((child) => {
            if (child.get("name")?.includes("collapse")) return;
            if (child.get("name")?.includes("root-shape")) return;
            child.hide();
          });
          if (!maskLabel) {
            maskLabel = group.addShape("text", {
              attrs: {
                fontSize: 20,
                x: 0,
                y: 0,
                text: effect,
                textAlign: "center",
                textBaseline: "middle",
                fill: colors.Normal,
              },

              name: "mask-label-shape",
            });
            const collapseRect = group.find((ele) => ele.get("name") === "collapse-back");
            const collapseText = group.find((ele) => ele.get("name") === "collapse-text");
            collapseRect?.toFront();
            collapseText?.toFront();
          } else {
            maskLabel.show();
          }
          maskLabel.animate({ opacity: 1 }, 200);
          return maskLabel;
        }
        group.get("children").forEach((child) => {
          if (child.get("name")?.includes("collapse")) return;
          child.show();
        });
        maskLabel?.animate(
          { opacity: 0 },
          {
            duration: 200,
            callback: () => maskLabel.hide(),
          },
        );
        if (form) {
          // 更新表单信息
          const effectEl = group.findAllByName("effect")[0];
          const nameShapeEl = group.findAllByName("name-shape")[0];
          effectEl?.attr("text", effect);
          nameShapeEl?.attr("text", name);
          maskLabel?.attr("text", effect);
        }
        this.updateLinkPoints(config, group);
      },
      setState(name, value, item) {
        if (name === "collapse") {
          const group = item.getContainer();
          const collapseText = group.find((e) => e.get("name") === "collapse-text");
          if (collapseText) {
            if (!value) {
              collapseText.attr({
                text: "-",
              });
            } else {
              collapseText.attr({
                text: "+",
              });
            }
          }
        }
      },
      getAnchorPoints() {
        return [
          [0, 0.5],
          [1, 0.5],
        ];
      },
    },
    "rect",
  );
  G6.registerNode(
    "policy-node",
    {
      shapeType: "policy-node",
      draw(config, group) {
        const { groupType, collapsed } = config;

        const rect = group.addShape("rect", {
          attrs: {
            x: nodeOrigin.x,
            y: nodeOrigin.y,
            ...rectConfig,
          },
          name: "root-shape",
        });

        const rectBBox = rect.getBBox();
        // label title
        group.addShape("text", {
          attrs: {
            ...textConfig,
            fontSize: 20,
            x: 0,
            y: 0,
            text: groupType,
            textAlign: "center",
            textBaseline: "middle",
            fill: colors.Normal,
          },

          name: "name-item",
        });
        addEdit(group, config.id);
        addChild(group, config.id);
        addRemove(group, config.id);
        // collapse rect
        if (config.children) {
          addCollapse(group, config.id, collapsed);
        }

        this.drawLinkPoints(config, group);
        return rect;
      },
      update(config, item) {
        const { form, groupType } = config;
        const group = item.getContainer();
        if (form) {
          // 更新表单信息
          const nameEl = group.findAllByName("name-item")[0];
          nameEl?.attr("text", groupType);
        }
        this.updateLinkPoints(config, group);
        this.updateLinkPoints(config, group);
      },
      setState(name, value, item) {
        if (name === "collapse") {
          const group = item.getContainer();
          const collapseText = group.find((e) => e.get("name") === "collapse-text");
          if (collapseText) {
            if (!value) {
              collapseText.attr({
                text: "-",
              });
            } else {
              collapseText.attr({
                text: "+",
              });
            }
          }
        }
      },
      getAnchorPoints() {
        return [
          [0, 0.5],
          [1, 0.5],
        ];
      },
    },
    "rect",
  );
  G6.registerNode(
    "policy-rule",
    {
      shapeType: "policy-rule",
      draw(config, group) {
        const { oType, operator, key, value } = config;
        const rect = group.addShape("rect", {
          attrs: {
            x: nodeOrigin.x,
            y: nodeOrigin.y,
            ...rectConfig,
          },
          name: "root-shape",
        });

        const rectBBox = rect.getBBox();
        const valueMap = Array.isArray(value) ? value.join(",") : value;
        const text = `${oType}.${key} ${operator}\n${valueMap}`;
        config.name = `[${oType}.${key}] ${operator}\n[${valueMap}]`;
        // label title
        group.addShape("text", {
          attrs: {
            ...textConfig,
            fontSize: 12,
            lineHeight: 16,
            x: 0,
            y: 0,
            text,
            textAlign: "center",
            textBaseline: "middle",
            fill: colors.Normal,
            cursor: "pointer",
          },

          name: "rule-name-item",
        });
        addEdit(group, config.id);
        addChild(group, config.id);
        addRemove(group, config.id);

        this.drawLinkPoints(config, group);
        return rect;
      },
      update(config, item) {
        const { form, oType, operator, key, value } = config;
        const group = item.getContainer();
        const valueMap = Array.isArray(value) ? value.join(",") : value;
        if (form) {
          // 更新表单信息
          const nameEl = group.findAllByName("rule-name-item")[0];
          const text = `${oType}.${key} ${operator}\n${valueMap}`;
          config.name = `[${oType}.${key}] ${operator}\n[${valueMap}]`;
          nameEl?.attr("text", text);
        }
        this.updateLinkPoints(config, group);
      },
      setState(name, value, item) {},
      getAnchorPoints() {
        return [
          [0, 0.5],
          [1, 0.5],
        ];
      },
    },
    "rect",
  );
}

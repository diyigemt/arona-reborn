import { RouteRecordRaw } from "vue-router";

const PluginRouter: Array<RouteRecordRaw> = [
  {
    path: "plugin",
    meta: {
      title: "插件",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [
      {
        path: "",
        redirect: "base-preferences",
      },
      {
        path: "base-preferences",
        name: "base-preferences",
        meta: {
          title: "通用设置",
        },
        component: () => import("@/views/config/plugin/BasePreferences.vue"),
      },
      {
        path: "arona-preferences",
        name: "arona-preferences",
        meta: {
          title: "Arona偏好设置",
        },
        component: () => import("@/views/config/plugin/AronaPreferences.vue"),
      },
      {
        path: "arona-gacha",
        name: "arona-gacha",
        meta: {
          title: "抽卡设置",
        },
        component: () => import("@/views/config/plugin/GachaPoolPreferences.vue"),
      },
      {
        path: "kivotos-coffee",
        name: "kivotos-coffee",
        meta: {
          title: "咖啡厅设置",
        },
        component: () => import("@/views/config/plugin/CoffeePreferences.vue"),
      },
      {
        path: "chatbot",
        name: "chatbot-preferences",
        meta: {
          title: "闲聊设置",
        },
        component: () => import("@/views/config/plugin/ChatbotPreferences.vue"),
      },
      {
        path: "chatbot-stickers",
        name: "chatbot-stickers",
        meta: {
          title: "表情图库",
        },
        component: () => import("@/views/config/plugin/ChatbotStickerLibrary.vue"),
      },
    ],
  },
];
export default PluginRouter;

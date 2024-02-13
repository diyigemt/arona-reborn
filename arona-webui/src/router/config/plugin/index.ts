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
    ],
  },
];
export default PluginRouter;

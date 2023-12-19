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
        redirect: "arona-preferences",
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

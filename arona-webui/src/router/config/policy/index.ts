import { RouteRecordRaw } from "vue-router";

const PolicyRouter: Array<RouteRecordRaw> = [
  {
    path: "config",
    meta: {
      title: "策略",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [
      {
        path: "",
        redirect: "config-policy",
      },
      {
        path: "config-policy",
        name: "config-policy",
        meta: {
          title: "策略配置",
        },
        component: () => import("@/views/config/policy/UserPolicy.vue"),
      },
      {
        path: "config-arona-preferences",
        name: "config-arona-preferences",
        meta: {
          title: "Arona偏好设置",
        },
        component: () => import("@/views/config/plugin/AronaPreferences.vue"),
      },
    ],
  },
];
export default PolicyRouter;

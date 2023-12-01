import { RouteRecordRaw } from "vue-router";

const ConfigRouter: Array<RouteRecordRaw> = [
  {
    path: "config",
    meta: {
      title: "策略",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [
      {
        path: "",
        redirect: "config-arona-policy",
      },
      {
        path: "config-arona-policy",
        name: "config-arona-policy",
        meta: {
          title: "策略配置",
        },
        component: () => import("@/views/config/config/UserPolicy.vue"),
      },
    ],
  },
];
export default ConfigRouter;

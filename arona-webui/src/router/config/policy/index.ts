import { RouteRecordRaw } from "vue-router";

const PolicyRouter: Array<RouteRecordRaw> = [
  {
    path: "policy",
    meta: {
      title: "策略",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [
      {
        path: "",
        redirect: "config",
      },
      {
        path: "config",
        name: "policy-config",
        meta: {
          title: "策略配置",
        },
        component: () => import("@/views/config/policy/UserPolicy.vue"),
      },
    ],
  },
];
export default PolicyRouter;

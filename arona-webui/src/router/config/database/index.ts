import { RouteRecordRaw } from "vue-router";

const DatabaseRouter: Array<RouteRecordRaw> = [
  {
    path: "database",
    meta: {
      title: "数据库",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [],
  },
];
export default DatabaseRouter;

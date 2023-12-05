// 不需要鉴权的业务路由
import { RouteRecordRaw } from "vue-router";

const commonRoutes: Array<RouteRecordRaw> = [
  {
    path: "/login",
    component: () => import("@/views/home/Login.vue"),
  },
];

export default commonRoutes;

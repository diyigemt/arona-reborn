// 需要鉴权的业务路由 (走 router.beforeEach 守卫拦截).
import { RouteRecordRaw } from "vue-router";
import ConfigRoutes from "@/router/config";

const authRoutes: Array<RouteRecordRaw> = [
  {
    path: "/",
    redirect: "/login",
  },
  {
    path: "/home",
    name: "home",
    meta: {
      title: "",
      icon: "",
    },
    component: () => import("@/views/home/HomeIndex.vue"),
  },
  ...ConfigRoutes,
];

export default authRoutes;

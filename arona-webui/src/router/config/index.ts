// 需要鉴权的业务路由
import { RouteRecordRaw } from "vue-router";
import PolicyRouter from "@/router/config/policy";
import DatabaseRouter from "@/router/config/database";
import SettingRouter from "@/router/config/setting";

const ConfigRoutes: Array<RouteRecordRaw> = [
  {
    path: "/config",
    name: "config-index",
    redirect: "/config/home",
    meta: {
      title: "",
      icon: "",
    },
    component: () => import("@/views/config/ConfigIndex.vue"),
    children: [
      {
        path: "/config/home",
        name: "config-home",
        meta: {
          title: "",
          icon: "",
        },
        component: () => import("@/views/config/home/ConfigHome.vue"),
      },
      ...PolicyRouter,
      ...DatabaseRouter,
      ...SettingRouter,
    ],
  },
];

export default ConfigRoutes;

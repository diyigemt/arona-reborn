// 需要鉴权的业务路由
import { RouteRecordRaw } from "vue-router";
import PolicyRouter from "@/router/config/policy";
import ContactRouter from "@/router/config/contact";
import PluginRouter from "@/router/config/plugin";
import DatabaseRouter from "@/router/config/database";
import SettingRouter from "@/router/config/setting";
import UserRouter from "@/router/config/user";

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
      ...ContactRouter,
      ...PluginRouter,
      ...DatabaseRouter,
      ...SettingRouter,
      ...UserRouter,
    ],
  },
];

export default ConfigRoutes;

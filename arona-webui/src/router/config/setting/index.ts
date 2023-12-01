import { RouteRecordRaw } from "vue-router";

const SettingRouter: Array<RouteRecordRaw> = [
  {
    path: "setting",
    meta: {
      title: "设置",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [],
  },
];
export default SettingRouter;

import { RouteRecordRaw } from "vue-router";

const UserRouter: Array<RouteRecordRaw> = [
  {
    path: "user",
    meta: {
      title: "个人信息",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [
      {
        path: "",
        redirect: "profile",
      },
      {
        path: "profile",
        name: "profile",
        meta: {
          title: "个人信息",
        },
        component: () => import("@/views/config/user/UserProfile.vue"),
      },
    ],
  },
];
export default UserRouter;

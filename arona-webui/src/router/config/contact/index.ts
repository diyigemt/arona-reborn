import { RouteRecordRaw } from "vue-router";

const ContactRouter: Array<RouteRecordRaw> = [
  {
    path: "contact",
    meta: {
      title: "群",
    },
    component: () => import("@/components/SubPageIndex.vue"),
    children: [
      {
        path: "",
        redirect: "contact-manage",
      },
      {
        path: "manage",
        name: "manage",
        meta: {
          title: "我管理的群",
        },
        component: () => import("@/views/config/contact/ContactManage.vue"),
      },
      {
        path: "join",
        name: "join",
        meta: {
          title: "我加入的群",
        },
        component: () => import("@/views/config/contact/Contact.vue"),
      },
    ],
  },
];
export default ContactRouter;

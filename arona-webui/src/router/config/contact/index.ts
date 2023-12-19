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
        path: "contact-manage",
        name: "contact-manage",
        meta: {
          title: "群管理",
        },
        component: () => import("@/views/config/contact/Contact.vue"),
      },
      {
        path: "contact-join",
        name: "contact-join",
        meta: {
          title: "群管理",
        },
        component: () => import("@/views/config/contact/Contact.vue"),
      },
    ],
  },
];
export default ContactRouter;

import { createRouter, createWebHistory, Router, RouteRecordRaw } from "vue-router";
import NProgress from "nprogress";
import exceptionRoutes from "@/router/route.exception";
import authRoutes from "@/router/route.auth";
import commonRoutes from "@/router/route.common";
import useBaseStore from "@/store/base";

const routes: Array<RouteRecordRaw> = [
  // 无鉴权的业务路由 ex:登录
  ...commonRoutes,
  // 带鉴权的业务路由
  ...authRoutes,
  // 异常页必须放在路由匹配规则的最后
  ...exceptionRoutes,
];

const router: Router = createRouter({
  // 新的vue-router4 使用 history路由模式 和 base前缀
  history: createWebHistory(import.meta.env.VITE_BASE),
  routes,
});

const publicPathSet = new Set(commonRoutes.map((route) => route.path));

/**
 * 全局路由前置守卫: 设页面标题 + 鉴权重定向.
 * 未持有 token 且访问的不是公共路径时, 直接跳转到 /login, 避免后端 401 才发现.
 */
router.beforeEach((to) => {
  document.title = (to.meta.title as string) || import.meta.env.VITE_APP_TITLE;
  if (!NProgress.isStarted()) {
    NProgress.start();
  }
  if (!publicPathSet.has(to.path) && !useBaseStore().token) {
    NProgress.done();
    return { path: "/login" };
  }
  return true;
});

router.afterEach((to, from) => {
  // console.log("全局路由后置守卫：to,from\n", to, from);
  NProgress.done();
});

export default router;

import { defineConfig } from "vitest/config";
import { resolve } from "path";

// 独立于 vite.config.mts: 数据层为纯函数, 无需 unocss/vue 插件链, 保持测试快且无副作用.
// 仅复用 @/ alias, 以防被测模块经 @/interface 间接引入运行时值.
export default defineConfig({
  resolve: {
    alias: {
      "@": resolve(__dirname, "./src"),
    },
  },
  test: {
    include: ["src/**/*.spec.ts"],
    environment: "node",
  },
});

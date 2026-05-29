import { readFileSync } from "node:fs";
import js from "@eslint/js";
import tseslint from "typescript-eslint";
import pluginVue from "eslint-plugin-vue";
import prettierRecommended from "eslint-plugin-prettier/recommended";
import globals from "globals";

// unplugin-auto-import 生成的全局变量声明 (vue / pinia / vue-router / vue-i18n / @vueuse 的自动导入 API).
const autoImportGlobals = JSON.parse(
  readFileSync(new URL("./.eslintrc-auto-import.json", import.meta.url), "utf8"),
).globals;

export default tseslint.config(
  {
    ignores: [
      "node_modules",
      "dist",
      "dist-ssr",
      "*.local",
      "presets",
      "mock",
      "**/*.d.ts",
      "**/gstc.wasm.esm.min.js",
    ],
  },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  ...pluginVue.configs["flat/recommended"],
  {
    // .vue 文件的 <script lang="ts"> 交由 TS parser 解析 (vue-eslint-parser 负责外层模板).
    files: ["**/*.vue"],
    languageOptions: {
      parserOptions: { parser: tseslint.parser },
    },
  },
  {
    languageOptions: {
      ecmaVersion: "latest",
      sourceType: "module",
      globals: {
        ...globals.browser,
        ...globals.node,
        ...autoImportGlobals,
        // 编译器宏与项目级全局.
        defineProps: "readonly",
        defineEmits: "readonly",
        defineExpose: "readonly",
        defineOptions: "readonly",
        defineModel: "readonly",
        withDefaults: "readonly",
        GLOBAL_VAR: "readonly",
      },
    },
    rules: {
      "no-console": "off",
      "no-plusplus": "off",
      "no-param-reassign": "off",
      "no-use-before-define": "off",
      "no-underscore-dangle": "off",
      "no-unused-expressions": "off",
      "no-shadow": "off",
      "@typescript-eslint/no-shadow": ["error"],
      // 与原 .eslintrc.js 的 no-unused-expressions:off 对齐 (ts-eslint 8 recommended 默认开启此规则).
      "@typescript-eslint/no-unused-expressions": "off",
      // 路由异常页命名为 401/404 等单词组件名是有意为之.
      "vue/multi-word-component-names": "off",
      // vue-plugin 10 新增的风格规则; 本项目沿用 withDefaults + 必填 prop 写法, 不引入此项新增告警.
      "vue/no-required-prop-with-default": "off",
      "@typescript-eslint/ban-ts-comment": "off",
      "@typescript-eslint/no-non-null-assertion": "off",
      "@typescript-eslint/no-unused-vars": "off",
      "@typescript-eslint/explicit-module-boundary-types": "off",
      "@typescript-eslint/no-explicit-any": "off",
      "@typescript-eslint/no-unsafe-call": "off",
      "@typescript-eslint/no-unsafe-return": "off",
      "@typescript-eslint/no-unsafe-assignment": "off",
      "@typescript-eslint/no-unsafe-member-access": "off",
    },
  },
  prettierRecommended,
);

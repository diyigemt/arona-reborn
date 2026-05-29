// 从 @unocss/* 子包按需引入, 而非从 "unocss" 桶包: 后者会 eager 加载 transformer-attributify-jsx,
// 进而拉起原生依赖 oxc-parser (本项目并未使用 JSX attributify), 在部分平台上其原生二进制安装不稳定.
// defineConfig 在 unocss 中仅是恒等函数, 这里就地实现以彻底避免触达桶包.
import presetWind3 from "@unocss/preset-wind3";
import presetAttributify from "@unocss/preset-attributify";
import presetIcons from "@unocss/preset-icons";
import transformerDirectives from "@unocss/transformer-directives";
import transformerVariantGroup from "@unocss/transformer-variant-group";
import { presetDaisy } from "unocss-preset-daisy";

const defineConfig = <T>(config: T): T => config;

export default defineConfig({
  presets: [
    presetWind3(),
    presetAttributify(),
    presetIcons({
      scale: 1.5,
      warn: true,
    }),
    presetDaisy(),
  ],
  transformers: [transformerDirectives(), transformerVariantGroup()],
  theme: {},
});

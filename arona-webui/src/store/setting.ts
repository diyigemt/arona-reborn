import { defineStore } from "pinia";
import { SettingStoreState } from "./type";

const useSettingStore = defineStore({
  id: "setting",
  state: (): SettingStoreState => ({
    theme: {
      themeType: "亮蓝色",
      themeColor: "#2080F0FF",
    },
  }),
  getters: {
    getThemeType: (state: SettingStoreState) => state.theme.themeType,
    getThemeColor: (state: SettingStoreState) => state.theme.themeColor,
  },
  actions: {

  },
  persist: {
    key: "setting",
  },
});

export default useSettingStore;

import { defineStore } from "pinia";
import { BaseStoreState } from "./type";

const useBaseStore = defineStore({
  id: "common",
  state: (): BaseStoreState => ({
    token: "",
  }),
  getters: {},
  actions: {
    setToken(token: string) {
      this.token = token;
    },
  },
  persist: {
    key: "base",
  },
});
export default useBaseStore;

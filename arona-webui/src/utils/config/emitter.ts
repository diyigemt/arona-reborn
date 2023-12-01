import EventEmitter from "eventemitter3";
import useBaseStore from "@/store/base";

type Events = {
  "api-update": undefined;
};

const emitter = new EventEmitter<Events>();

export function initEventBus() {
  const baseStore = useBaseStore();
}

export default emitter;

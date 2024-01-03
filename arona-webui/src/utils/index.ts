import { Ref } from "vue";

export function deepCopy<T>(data: T): T {
  return JSON.parse(JSON.stringify(data));
}

export function fillForm<IForm extends object>(form: IForm, data: IForm, keys: (keyof IForm)[]) {
  keys.forEach((key) => {
    const value = Reflect.get(data, key);
    Reflect.set(form, key, value);
  });
}

export function dataFilterChain<T, Form extends object>(
  data: T[],
  filterForm: Form,
  filterFn: ((data: T[], value: unknown) => T[])[],
  keys: (keyof Form)[],
): T[] {
  if (keys.length !== filterFn.length) {
    return data;
  }
  filterFn.forEach((fn, index) => {
    const key = keys[index];
    const value = Reflect.get(filterForm, key);
    if (!value || (Array.isArray(value) && value.length === 0)) {
      return;
    }
    data = fn(data, value);
  });
  return data;
}

/**
 * 返回start至end之间的随机整数
 * @param start
 * @param end
 * @return [start,end)
 */
export function randomInt(start: number, end: number) {
  return Math.floor(Math.random() * (end - start) + start);
}

/**
 * 随机选取arr里的一个item
 * @param arr
 */
export function randomArrayItem<T>(arr: T[]): T {
  const index = randomInt(0, arr.length);
  return arr[index];
}

/**
 * 随机选取arr里0至size-1的一个item,并放回最后
 * @param arr
 */
export function pickRandomArrayItemAndPutBack<T>(arr: T[]) {
  if (arr.length < 1) {
    return {
      arr,
      item: arr[0],
    };
  }
  const pickItem = randomArrayItem(arr.slice(0, arr.length - 1));
  arr.splice(arr.indexOf(pickItem), 1);
  arr.push(pickItem);
  return {
    arr,
    item: pickItem,
  };
}

export function useTableInlineEditor<T extends { id: string; edit: boolean }>(
  datasource: Ref<T[]>,
  onConfirm: (data: T) => Promise<unknown> = () => Promise.resolve(),
  onEdit: (data: T) => Promise<unknown> = () => Promise.resolve(),
  onCancel: (data: T) => Promise<unknown> = () => Promise.resolve(),
) {
  const editCache = ref<T>() as Ref<T>;
  const currentEdit = ref<T>() as Ref<T>;
  return {
    cache: currentEdit,
    onEdit(data: T) {
      editCache.value = JSON.parse(JSON.stringify(data));
      onEdit(data).then(() => {
        if (currentEdit.value) {
          currentEdit.value.edit = false;
        }
        currentEdit.value = data;
        currentEdit.value.edit = true;
      });
    },
    onConfirm(data: T) {
      onConfirm(data).then((next) => {
        currentEdit.value.edit = false;
      });
    },
    onCancel(data: T) {
      if (editCache.value) {
        currentEdit.value = editCache.value;
        const index = datasource.value.findIndex((it) => it.id === currentEdit.value.id);
        if (index !== -1) {
          datasource.value.splice(index, 1, editCache.value);
        }
      }
      return onCancel(data).then((next) => {
        currentEdit.value.edit = false;
      });
    },
  };
}

export function useForceUpdate(init: boolean = true) {
  const visible = ref(init);
  return {
    visible,
    update() {
      visible.value = false;
      nextTick(() => {
        visible.value = true;
      }).then();
    },
  };
}

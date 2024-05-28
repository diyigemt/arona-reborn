import { PropType } from "vue";
import { ElButton, ElCol, ElForm, ElFormItem, ElInput, ElPopover, ElRow, ElSwitch } from "element-plus";
import { Plus } from "@element-plus/icons-vue";
import { CustomMenuButton, CustomMenuRow } from "@/interface";
import { infoMessage } from "@/utils/message";

export const CustomButton = defineComponent({
  props: {
    button: {
      type: Object as PropType<CustomMenuButton>,
      required: true,
    },
  },
  emits: ["update:button", "delete"],
  setup(prop, { emit }) {
    const data = ref(prop.button);
    function onDeleteButton() {
      emit("delete");
    }
    const slots = {
      default: () => (
        <>
          <ElForm model={data.value} labelPosition={"left"} labelWidth={80}>
            <ElFormItem label={"标签:"} prop={"label"}>
              <ElInput v-model={data.value.label} placeholder={"标签"} />
            </ElFormItem>
            <ElFormItem label={"数据:"} prop={"data"}>
              <ElInput v-model={data.value.data} placeholder={"数据"} />
            </ElFormItem>
            <ElFormItem label={"自动发送:"} prop={"enter"}>
              <ElSwitch v-model={data.value.enter} activeValue={true} inactiveValue={false} />
            </ElFormItem>
            <ElFormItem class={"mb-0!"}>
              <ElButton type={"danger"} onClick={onDeleteButton}>
                删除
              </ElButton>
            </ElFormItem>
          </ElForm>
        </>
      ),
      reference: () => (
        <ElButton plain class={"w-100%"}>
          {data.value.label}
        </ElButton>
      ),
    };
    return () => (
      <ElCol class={"flex-1!"}>
        <ElPopover trigger={"click"} v-slots={slots} width={300} />
      </ElCol>
    );
  },
});
export const CustomRow = defineComponent({
  props: {
    row: {
      type: Object as PropType<CustomMenuRow>,
      required: true,
    },
  },
  emits: ["update:row"],
  setup(props, { emit }) {
    const buttons = ref(props.row.buttons);
    const overflow = computed(() => buttons.value.length >= 5);
    function onButtonUpdate() {
      update();
    }
    function onButtonAdd() {
      if (buttons.value.length >= 5) {
        infoMessage("放不下了");
        return;
      }
      buttons.value.push({
        label: "",
        data: "",
        enter: false,
      });
      update();
    }
    function onButtonDelete(index: number) {
      buttons.value.splice(index, 1);
      update();
    }
    function update() {
      emit("update:row", {
        buttons: buttons.value,
      });
    }
    return () => (
      <ElRow gutter={16}>
        {buttons.value.map((it, index) => (
          <CustomButton button={it} key={index} onUpdate:button={onButtonUpdate} onDelete={() => onButtonDelete(index)} />
        ))}
        <ElCol
          class={"max-w-60px!"}
          style={{ opacity: overflow.value ? 0 : 1, "pointer-events": overflow.value ? "none" : "initial" }}
        >
          <ElButton type={"primary"} icon={Plus} onClick={onButtonAdd} />
        </ElCol>
      </ElRow>
    );
  },
});

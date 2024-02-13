import { PropType } from "vue";
import { ElButton, ElCol, ElForm, ElFormItem, ElInput, ElPopover, ElRow, ElSwitch } from "element-plus";
import { Delete, Plus } from "@element-plus/icons-vue";
import { CustomMenuButton, CustomMenuRow } from "@/interface";

export const CustomButton = defineComponent({
  props: {
    button: {
      type: Object as PropType<CustomMenuButton>,
      required: true,
    },
  },
  emits: ["update:button"],
  setup(prop, { emit }) {
    const data = ref(prop.button);
    watch(
      () => data.value,
      () => {
        emit("update:button", data.value);
      },
      { deep: true },
    );
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
              <ElButton type={"danger"}>删除</ElButton>
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
  setup(props) {
    function onButtonUpdate(b: CustomMenuButton, idx: number) {
      console.log(b, idx);
    }
    return () => (
      <ElRow gutter={16}>
        {props.row.buttons.map((it, index) => (
          <CustomButton button={it} key={index} onUpdate:button={onButtonUpdate}></CustomButton>
        ))}
        <ElCol class={"max-w-60px!"}>
          <ElButton type={"primary"} icon={Plus} />
        </ElCol>
      </ElRow>
    );
  },
});

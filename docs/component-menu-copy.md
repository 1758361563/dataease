# 组件菜单复制修复

组件右上角菜单阻止了画布选中事件，但原 copyComponent 调用无参数 copy()，依赖全局 curComponent。因此未选中时没有目标，选中其他组件时可能复制错误对象。

菜单改为显式传入 element；copy() 保留无参数调用，继续支持键盘复制当前选择与选区复制。现有深度复制、图表配置复制和布局插入流程保持。副本的选中索引改为查找实际容器内的位置，解决 Tab 内组件使用主画布索引的问题。

验证使用实际 copy store 的动作，以及从 dvMain 源码提取的实际 addCopyComponent/updateCopyCanvasView 方法，在隔离状态下执行：未选中复制、其他组件被选中、键盘回退、同 Tab 内复制、整个 Tab 的递归复制、子组件 ID 与 canvasId 重映射、年月维度配置和全局参数公式保留、副本配置独立。ComponentEditBar Vue 编译和两份修改源码 ESLint 通过。测试文件为 core/core-frontend/tests/componentCopy.test.cjs。

本次只修改前端，刷新编辑页面后即可使用，不需要重编后端。

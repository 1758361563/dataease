<script lang="ts">
import { h } from 'vue'
import { resourceNavIcon } from './navIcons'
import { NAV_MENU_TIMING } from './navMenu'
import icon_expandDown_filled from '@/assets/svg/scent/chevron-down.svg'
import { ElMenuItem, ElSubMenu } from 'element-plus-secondary'

const title = props => {
  const { title } = props?.menu?.meta || {}
  const path = props?.menu?.path || ''
  const icon = props.index ? resourceNavIcon(path) : null
  return [...(icon ? [h(icon)] : []), h('span', null, { default: () => title })]
}

const HeaderMenuItem = props => {
  if (!props) return null
  const { children = [], hidden, path } = props?.menu || {}
  if (hidden) {
    return null
  }

  if (children?.length) {
    return h(
      ElSubMenu,
      {
        index: path,
        'popper-class': 'scent-nav-popper',
        ...NAV_MENU_TIMING,
        expandCloseIcon: icon_expandDown_filled,
        expandOpenIcon: icon_expandDown_filled
      },
      {
        title: () => title(props),
        default: () => children.map(ele => h(HeaderMenuItem, { menu: ele, index: path }))
      }
    )
  }

  return h(
    ElMenuItem,
    { index: props.index ? `${props.index}/${path}` : path },
    {
      title: () => title(props)
    }
  )
}
export default HeaderMenuItem
</script>

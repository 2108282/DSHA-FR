#!/usr/bin/env python3
"""校验冻结日志界面契约，不调用编译器或设备接口。"""
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1] / 'app/src/main'
ANDROID = '{http://schemas.android.com/apk/res/android}'


def main():
    layout = ET.parse(ROOT / 'res/layout/dialog_freeze_log.xml')
    nodes = {n.get(ANDROID + 'id'): n for n in layout.iter()}
    assert any(n.tag.endswith('ModernCardView') for n in layout.iter())
    for name in ('freeze_log_delete', 'freeze_log_location', 'freeze_log_close', 'freeze_log_scroll', 'freeze_log_content'):
        assert '@+id/' + name in nodes
    assert nodes['@+id/freeze_log_scroll'].get(ANDROID + 'layout_weight') == '1'
    config = ET.parse(ROOT / 'res/layout/fragment_config.xml')
    config_ids = {n.get(ANDROID + 'id') for n in config.iter()}
    assert '@+id/config_btn_clear_freeze_log' not in config_ids
    assert '@+id/config_btn_freeze_whitelist' in config_ids
    fragment = (ROOT / 'java/com/deepseekharness/app/ui/ConfigFragment.java').read_text()
    presenter = (ROOT / 'java/com/deepseekharness/app/ui/contract/ConfigPresenter.java').read_text()
    actions = (ROOT / 'java/com/deepseekharness/app/ui/contract/ConfigActions.java').read_text()
    assert 'CardSheet.' not in fragment and 'CardPage ' not in fragment
    assert 'MonetEngine.applyToViewTree(root' in fragment
    assert 'android.view.Gravity.CENTER' in fragment and '* .9f' in fragment
    assert 'confirmFreezeLogDeletion()' in fragment and 'R.layout.dialog_confirm_action' in fragment
    assert 'actions.onDeleteFreezeLog()' in fragment
    assert 'FreezeLogRepository.delete(' in presenter
    assert 'FreezeLogRepository.location(' in presenter
    assert 'FreezeWhitelistRepository.prepareLocation(' in presenter
    assert 'RootFileLocation.open(' in fragment
    for retired in ('onSaveFreezeWhitelist', 'onShowFreezeWhitelist', 'onClearFreezeLog', 'freezeWhitelistInput'):
        assert retired not in fragment + presenter + actions
    print('PASS Skia/莫奈/居中大窗口、固定操作区、删除确认、日志位置及白名单位置入口；未编译')


if __name__ == '__main__':
    main()

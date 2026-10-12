#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""内置插件清单生成器（第 137 轮）。

扫 `<assets>/plugins/*.apk`，为每个算 sha256，写出 `<assets>/plugins/manifest.txt`：

    <id> \t <文件名> \t <sha256 全量>

- **id = 文件名去掉 .apk**（和外部插件的 id 是同一个命名空间，所以内置 id 要取不容易撞的名字）。
- ★ **哈希必须现算**：手抄哈希的话，改了 APK 忘了改清单，运行时就把它当成"被改过的内置插件"
  而静默回落成外部插件（那种 bug 极难查）；这里现算 ⇒ 清单永远和包里那份一致。
- 版本号**不写进清单**：运行期用 `sha256` 前 8 位当登记表的版本键（内容变了就是新版本，
  这样"本体带新版内置插件"能自然升级、也能被回滚/已知可用那套机制正确对待）。
- 目录不存在 / 没有 apk ⇒ 写一份空清单（运行时就当"本体没带内置插件"，零成本）。

退出码非 0 = 生成失败（构建应当中断，别让包和清单对不上）。
"""
import hashlib
import os
import sys


def sha256_of(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 16), b''):
            h.update(chunk)
    return h.hexdigest()


def main():
    if len(sys.argv) != 2:
        sys.stderr.write('usage: builtin-manifest.py <assets-dir>\n')
        return 2
    assets = sys.argv[1]
    pdir = os.path.join(assets, 'plugins')
    if not os.path.isdir(pdir):
        sys.stderr.write('FAIL: no plugins dir under %s\n' % assets)
        return 1

    lines = []
    apks = sorted(f for f in os.listdir(pdir) if f.endswith('.apk'))
    for name in apks:
        pid = name[:-4]
        if '\t' in pid or '=' in pid or not pid:
            sys.stderr.write('FAIL: bad built-in plugin id in file name: %r\n' % name)
            return 1
        lines.append('%s\t%s\t%s' % (pid, name, sha256_of(os.path.join(pdir, name))))

    out = os.path.join(pdir, 'manifest.txt')
    with open(out, 'w', encoding='utf-8', newline='\n') as f:
        for ln in lines:
            f.write(ln + '\n')
    print('   builtin manifest: %d plugin(s) -> %s' % (len(lines), out))
    for ln in lines:
        print('     ' + ln.replace('\t', '  '))
    return 0


if __name__ == '__main__':
    sys.exit(main())

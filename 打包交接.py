"""
打包项目，带回另一台机器继续开发。

用 Python 而不是 bat 的原因：
  - bat 在 Git Bash 下编码会乱（中文全变问号）
  - PowerShell 的 Compress-Archive 会触发安全策略

用法：
    python 打包交接.py

产出：
    outputs/SpeakInspire-handover.zip
      - SpeakInspire/  项目源码 + git 历史
      - 交接说明.md
      - SpeakInspire/local.properties.示例
"""

import os
import sys
import zipfile

PROJ = r'D:\work2\AI\SpeakInspire'
OUT = r'D:\work2\AI\outputs'
ZIP = os.path.join(OUT, 'SpeakInspire-handover.zip')

# 构建产物与 IDE 配置不需要带走
EXCLUDE_DIRS = {'build', '.gradle', '.idea', '.git', 'node_modules'}
EXCLUDE_FILES = {'local.properties'}
EXCLUDE_EXT = {'.apk', '.png', '.log', '.zip'}


def collect_sources(z):
    """收项目源码"""
    n = 0
    for root, dirs, files in os.walk(PROJ):
        dirs[:] = [d for d in dirs if d not in EXCLUDE_DIRS]
        for f in files:
            if f in EXCLUDE_FILES or os.path.splitext(f)[1] in EXCLUDE_EXT:
                continue
            full = os.path.join(root, f)
            rel = os.path.join('SpeakInspire', os.path.relpath(full, PROJ))
            z.write(full, rel)
            n += 1
    return n


def collect_git(z):
    """收 .git，保留提交历史——能看到每个决策的理由"""
    n = 0
    gitdir = os.path.join(PROJ, '.git')
    if not os.path.isdir(gitdir):
        return 0
    for root, _, files in os.walk(gitdir):
        for f in files:
            full = os.path.join(root, f)
            # 关键：必须带 SpeakInspire/ 前缀，否则解压后
            # .git 会落在包根目录而不是项目里，git 会认不出来
            rel = os.path.join('SpeakInspire', os.path.relpath(full, PROJ))
            try:
                z.write(full, rel)
                n += 1
            except OSError:
                # 有些 git 内部文件可能正在被占用，跳过
                pass
    return n


def add_extras(z):
    """附交接文档与 local.properties 示例"""
    doc = os.path.join(OUT, '交接说明.md')
    if os.path.exists(doc):
        z.write(doc, '交接说明.md')

    # local.properties 每台机器不同，本身是 gitignore 的，
    # 所以这里给一份示例文件，附上改动说明
    sample = (
        '# 本文件每台机器不同，按自己的路径改\n'
        '# Windows 路径里的冒号和反斜杠必须转义\n'
        '#   示例：sdk.dir=D\\:\\\\soft\\\\AndroidSdk\n'
        '#\n'
        '# 不确定的话，用 Android Studio 打开项目，它会自动生成正确的。\n\n'
        'sdk.dir=D\\:\\\\soft\\\\AndroidSdk\n'
    )
    z.writestr('SpeakInspire/local.properties.示例', sample.encode('utf-8'))


def main():
    if not os.path.exists(PROJ):
        print('找不到项目目录：%s' % PROJ)
        sys.exit(1)

    print('打包中...')

    with zipfile.ZipFile(ZIP, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        n = collect_sources(z)
        add_extras(z)

    # git 用单独一轮压缩，压缩率要求低但更看重速度
    with zipfile.ZipFile(ZIP, 'a', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        n += collect_git(z)

    size = os.path.getsize(ZIP) / 1024 / 1024
    print('')
    print('完成：%d 个条目，%.2f MB' % (n, size))
    print('输出：%s' % ZIP)


if __name__ == '__main__':
    main()
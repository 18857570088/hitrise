from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_ALIGN_VERTICAL, WD_TABLE_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Inches, Pt, RGBColor


ROOT = Path(r"D:\2026\202605\hitrise")
DOCS = ROOT / "hitrise-docs"
DEPLOY = ROOT / "hitrise-deploy"
OUT = DOCS / "HitRise_用户手册.docx"

ICON = DEPLOY / "icon" / "HitRise_icon_512.png"
IMG_TRAINING = DEPLOY / "hitrise_training_layout_latest.png"
IMG_SOUND = DEPLOY / "hitrise_sound_settings_top.png"
IMG_ACHIEVEMENTS = DEPLOY / "hitrise_achievements_screen.png"


ACCENT = "00A865"
ACCENT_DARK = "063F2A"
ACCENT_SOFT = "E9FFF2"
BLUE = "0D4F8B"
ORANGE = "E07010"
RED = "B83232"
TEXT = "1A1F24"
MUTED = "52616B"
LIGHT = "F5F7F8"


def set_cell_shading(cell, fill):
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_border(cell, color="D6DEE4", size="6"):
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    borders = tc_pr.first_child_found_in("w:tcBorders")
    if borders is None:
        borders = OxmlElement("w:tcBorders")
        tc_pr.append(borders)
    for edge in ("top", "left", "bottom", "right"):
        tag = "w:{}".format(edge)
        element = borders.find(qn(tag))
        if element is None:
            element = OxmlElement(tag)
            borders.append(element)
        element.set(qn("w:val"), "single")
        element.set(qn("w:sz"), size)
        element.set(qn("w:space"), "0")
        element.set(qn("w:color"), color)


def set_cell_margins(cell, top=120, start=120, bottom=120, end=120):
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    margins = tc_pr.first_child_found_in("w:tcMar")
    if margins is None:
        margins = OxmlElement("w:tcMar")
        tc_pr.append(margins)
    for name, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = margins.find(qn(f"w:{name}"))
        if node is None:
            node = OxmlElement(f"w:{name}")
            margins.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def set_width(cell, width_cm):
    tc_pr = cell._tc.get_or_add_tcPr()
    width = tc_pr.first_child_found_in("w:tcW")
    if width is None:
        width = OxmlElement("w:tcW")
        tc_pr.append(width)
    width.set(qn("w:w"), str(int(width_cm * 567)))
    width.set(qn("w:type"), "dxa")


def set_run_font(run, size=None, bold=None, color=None, font="Microsoft YaHei"):
    run.font.name = font
    run._element.rPr.rFonts.set(qn("w:eastAsia"), font)
    if size is not None:
        run.font.size = Pt(size)
    if bold is not None:
        run.font.bold = bold
    if color:
        run.font.color.rgb = RGBColor.from_string(color)


def add_run(paragraph, text, size=None, bold=None, color=None):
    run = paragraph.add_run(text)
    set_run_font(run, size=size, bold=bold, color=color)
    return run


def style_paragraph(paragraph, size=10.5, color=TEXT, before=0, after=6, line_spacing=1.15):
    fmt = paragraph.paragraph_format
    fmt.space_before = Pt(before)
    fmt.space_after = Pt(after)
    fmt.line_spacing = line_spacing
    for run in paragraph.runs:
        set_run_font(run, size=size, color=color)


def add_body(doc, text, size=10.5, color=TEXT, after=6):
    p = doc.add_paragraph()
    add_run(p, text, size=size, color=color)
    style_paragraph(p, size=size, color=color, after=after)
    return p


def add_h1(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(10)
    p.paragraph_format.space_after = Pt(8)
    add_run(p, text, size=20, bold=True, color=ACCENT_DARK)
    return p


def add_h2(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(8)
    p.paragraph_format.space_after = Pt(5)
    add_run(p, text, size=14.5, bold=True, color=ACCENT_DARK)
    return p


def add_h3(doc, text):
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(5)
    p.paragraph_format.space_after = Pt(3)
    add_run(p, text, size=11.5, bold=True, color=BLUE)
    return p


def add_bullets(doc, items):
    for item in items:
        p = doc.add_paragraph(style="List Bullet")
        add_run(p, item, size=10.2, color=TEXT)
        p.paragraph_format.space_after = Pt(3)
        p.paragraph_format.left_indent = Cm(0.45)


def add_numbered(doc, items):
    for index, item in enumerate(items, 1):
        p = doc.add_paragraph()
        add_run(p, f"{index}. {item}", size=10.2, color=TEXT)
        p.paragraph_format.space_after = Pt(3)
        p.paragraph_format.left_indent = Cm(0.35)


def add_callout(doc, title, body, fill=ACCENT_SOFT, border=ACCENT):
    table = doc.add_table(rows=1, cols=1)
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    cell = table.cell(0, 0)
    set_cell_shading(cell, fill)
    set_cell_border(cell, border, "10")
    set_cell_margins(cell, 150, 170, 150, 170)
    p = cell.paragraphs[0]
    add_run(p, title, size=10.5, bold=True, color=ACCENT_DARK)
    p.paragraph_format.space_after = Pt(3)
    p2 = cell.add_paragraph()
    add_run(p2, body, size=9.8, color=TEXT)
    p2.paragraph_format.space_after = Pt(0)
    doc.add_paragraph().paragraph_format.space_after = Pt(3)
    return table


def add_info_table(doc, rows, widths=None, header_fill=ACCENT_DARK):
    table = doc.add_table(rows=1, cols=len(rows[0]))
    table.alignment = WD_TABLE_ALIGNMENT.CENTER
    table.style = "Table Grid"
    hdr = table.rows[0].cells
    for idx, text in enumerate(rows[0]):
        if widths:
            set_width(hdr[idx], widths[idx])
        set_cell_shading(hdr[idx], header_fill)
        set_cell_border(hdr[idx], "C9D4DA")
        set_cell_margins(hdr[idx], 130, 130, 130, 130)
        hdr[idx].vertical_alignment = WD_ALIGN_VERTICAL.CENTER
        p = hdr[idx].paragraphs[0]
        p.alignment = WD_ALIGN_PARAGRAPH.CENTER
        add_run(p, text, size=9.7, bold=True, color="FFFFFF")
    for row_data in rows[1:]:
        cells = table.add_row().cells
        for idx, text in enumerate(row_data):
            if widths:
                set_width(cells[idx], widths[idx])
            set_cell_shading(cells[idx], "FFFFFF")
            set_cell_border(cells[idx], "D6DEE4")
            set_cell_margins(cells[idx], 120, 130, 120, 130)
            cells[idx].vertical_alignment = WD_ALIGN_VERTICAL.CENTER
            p = cells[idx].paragraphs[0]
            p.alignment = WD_ALIGN_PARAGRAPH.LEFT if idx != 0 else WD_ALIGN_PARAGRAPH.CENTER
            add_run(p, text, size=9.2, color=TEXT)
    doc.add_paragraph().paragraph_format.space_after = Pt(4)
    return table


def add_picture_if_exists(doc, path, caption, width_in=5.3):
    if not path.exists():
        return
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run = p.add_run()
    run.add_picture(str(path), width=Inches(width_in))
    p.paragraph_format.space_after = Pt(2)
    cap = doc.add_paragraph()
    cap.alignment = WD_ALIGN_PARAGRAPH.CENTER
    add_run(cap, caption, size=8.8, color=MUTED)
    cap.paragraph_format.space_after = Pt(8)


def setup_document():
    doc = Document()
    section = doc.sections[0]
    section.page_width = Cm(21)
    section.page_height = Cm(29.7)
    section.top_margin = Cm(1.8)
    section.bottom_margin = Cm(1.8)
    section.left_margin = Cm(1.7)
    section.right_margin = Cm(1.7)

    styles = doc.styles
    styles["Normal"].font.name = "Microsoft YaHei"
    styles["Normal"]._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
    styles["Normal"].font.size = Pt(10.5)
    for style_name in ["List Bullet", "List Number"]:
        styles[style_name].font.name = "Microsoft YaHei"
        styles[style_name]._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        styles[style_name].font.size = Pt(10.2)
    return doc


def add_cover(doc):
    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(22)
    if ICON.exists():
        r = p.add_run()
        r.add_picture(str(ICON), width=Inches(1.55))

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_before = Pt(18)
    add_run(p, "HitRise", size=30, bold=True, color=ACCENT_DARK)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    add_run(p, "智能拳击速度球 APP 用户手册", size=19, bold=True, color=TEXT)

    p = doc.add_paragraph()
    p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    p.paragraph_format.space_after = Pt(18)
    add_run(p, "适用于 HitRise Android APP 与 SENBALL# 智能拳击球设备", size=11.5, color=MUTED)

    rows = [
        ["项目", "说明"],
        ["APP 显示名称", "HitRise"],
        ["设备名称前缀", "SENBALL#"],
        ["产品码", "HTR01"],
        ["当前版本", "1.0.0"],
        ["手册日期", "2026-05-21"],
    ]
    add_info_table(doc, rows, widths=[4.3, 10.4], header_fill=ACCENT_DARK)

    add_callout(
        doc,
        "使用前请先确认安全环境",
        "训练前请检查智能拳击球安装是否牢固、绳线是否远离他人和易碎物品，并根据自身身体状况安排训练强度。HitRise 训练数据用于运动反馈和统计，不作为医疗诊断、康复评估或正式竞技裁判依据。",
        fill="FFF5E6",
        border=ORANGE,
    )
    doc.add_page_break()


def add_contents(doc):
    add_h1(doc, "目录")
    rows = [
        ["章节", "内容"],
        ["1", "产品简介与适用范围"],
        ["2", "首次使用与权限准备"],
        ["3", "蓝牙连接与电量状态"],
        ["4", "实时训练仪表盘"],
        ["5", "训练设置、节拍与音频"],
        ["6", "训练结束、回合战报与云同步"],
        ["7", "锻炼成果、榜单排名与个人中心"],
        ["8", "数据口径与计算公式"],
        ["9", "维护建议与常见问题"],
    ]
    add_info_table(doc, rows, widths=[2.0, 13.1])
    add_body(doc, "提示：本手册按实际使用流程组织，建议首次使用时按第 2 至第 5 章顺序完成设置。", color=MUTED)
    doc.add_page_break()


def build_manual():
    doc = setup_document()
    add_cover(doc)
    add_contents(doc)

    add_h1(doc, "1. 产品简介与适用范围")
    add_body(doc, "HitRise 是一款配合智能拳击速度球使用的 Android 训练 APP。APP 通过蓝牙连接 SENBALL# 设备，读取拳击次数、电量、充电状态和击打力度，并提供实时训练、回合战报、锻炼成果、榜单排名、个人中心、音效和背景音乐等功能。")
    add_callout(doc, "当前计数方式", "当前版本的拳击次数来自智能拳击球蓝牙协议中的“数据2”，不使用手机麦克风或声音识别计数。")
    add_h2(doc, "主要功能")
    add_bullets(doc, [
        "实时训练仪表盘：显示回合计时、总击打数、BPM、卡路里、等效燃脂量、力度条形图和连击识别。",
        "训练设置：支持工作时长、休息时长、回合数、自由/跟拍模式和 BPM 节拍速度设置。",
        "蓝牙连接：自动连接上次 SENBALL# 设备；训练中遇到蓝牙断开会自动重连并尝试恢复计数。",
        "训练战报：按回合记录训练时间、累计击拳数、卡路里、等效燃脂量、最大力度和平均力度。",
        "锻炼成果与榜单：按锻炼时间、拳击次数、最大力度、平均力度、卡路里、等效燃脂量统计。",
        "音频体验：支持云端音效试听和设置；背景音乐可选择“无背景音乐”或轻快运动音乐。",
        "多语言：APP 界面支持简体中文、English、Français、ไทย。",
    ])

    add_h1(doc, "2. 首次使用与权限准备")
    add_h2(doc, "安装与打开")
    add_numbered(doc, [
        "安装 HitRise APK 后打开 APP。",
        "确认智能拳击球设备已开机，并放在手机附近。",
        "根据系统提示授予蓝牙相关权限。部分 Android 系统还需要定位权限才能扫描低功耗蓝牙设备。",
        "首次使用建议进入右上角设置，完成语言、蓝牙和音频设置。",
    ])
    add_h2(doc, "权限说明")
    add_info_table(doc, [
        ["权限", "用途", "说明"],
        ["蓝牙", "扫描、连接并接收 SENBALL# 数据", "用于拳数、力度、电量和充电状态读取。"],
        ["定位相关", "支持部分系统的蓝牙扫描", "APP 不主动获取或展示您的位置轨迹。"],
        ["网络", "同步训练、成果、榜单和音频资源", "网络不可用时，部分云端功能可能暂时不可刷新。"],
        ["图片/存储", "头像、分享海报等", "仅在用户选择图片或分享时使用。"],
    ], widths=[3.0, 5.2, 6.6])
    add_callout(doc, "安全提示", "训练前请确认设备安装牢固、手机放置稳定、周围没有人员靠近设备摆动范围。儿童使用时应由成人陪同。", fill="FFF5E6", border=ORANGE)

    add_h1(doc, "3. 蓝牙连接与电量状态")
    add_h2(doc, "连接设备")
    add_numbered(doc, [
        "点击主界面右上角设置图标。",
        "在蓝牙区域点击“扫描”。APP 会筛选名称以 SENBALL# 开头且最后 1 位为英文字母的设备。",
        "选择需要连接的设备，点击“连接”。如果只扫描到一个设备，APP 可能自动选中。",
        "连接成功后，主界面顶部蓝牙图标显示为蓝色，并显示电量或充电状态。",
    ])
    add_h2(doc, "电量显示规则")
    add_info_table(doc, [
        ["设备上报值", "APP 显示", "含义"],
        ["0-100", "对应百分比，例如 94%", "设备当前电量。"],
        ["101", "充电", "设备正在充电。"],
        ["102", "充满", "设备已充满。"],
        ["无数据", "-- 或读取中", "等待设备上报，请保持连接。"],
    ], widths=[3.3, 4.0, 7.5])
    add_h2(doc, "复杂蓝牙环境建议")
    add_bullets(doc, [
        "展会、商场、赛事现场蓝牙设备密集，建议手机与智能拳击球保持 1 米以内。",
        "尽量避免同时用多台手机连接同一设备。",
        "如训练中出现短暂断开，APP 会自动重连；重连期间请不要反复手动点击连接或断开。",
        "如果长时间无法恢复，请退出训练，关闭再开启手机蓝牙，并重启智能拳击球后重新连接。",
    ])

    add_h1(doc, "4. 实时训练仪表盘")
    add_picture_if_exists(doc, IMG_TRAINING, "实时训练界面示例：回合计时、指标卡、力度图、连击识别、开始/结束按钮", width_in=4.65)
    add_h2(doc, "仪表盘区域说明")
    add_info_table(doc, [
        ["区域", "显示内容", "用途"],
        ["回合计时环", "当前回合剩余时间或 3-2-1-Go", "帮助用户掌握回合节奏。"],
        ["三核心指标卡", "总击打数、BPM、卡路里/等效燃脂", "实时反馈训练量和运动强度。"],
        ["今日目标", "500 拳目标与已完成拳数", "用于日常训练激励。"],
        ["击打力度", "按力度渐变的条形图", "训练结束后展示最小、最大、平均力度。"],
        ["连击识别", "击打、重击、连击、三连击、爆发连击", "根据击打间隔和力度点亮识别结果。"],
        ["控制栏", "开始、结束", "控制训练开始和结束。"],
    ], widths=[3.2, 5.3, 6.2])
    add_h2(doc, "开始训练")
    add_numbered(doc, [
        "确认蓝牙已连接，电量状态正常显示。",
        "必要时点击“训练设置”调整回合、休息、节拍和模式。",
        "点击“开始”。APP 会在仪表盘中央显示并播放 3、2、1、Go。",
        "Go 之后 APP 开启设备陀螺仪计数，训练数据开始进入本次回合。",
    ])
    add_h2(doc, "结束训练")
    add_bullets(doc, [
        "可在训练中点击“结束”手动停止。",
        "达到设定回合后，训练自动结束。",
        "结束后 APP 会关闭陀螺仪计数，生成训练战报，并尝试同步到云端。",
    ])

    add_h1(doc, "5. 训练设置、节拍与音频")
    add_h2(doc, "训练设置")
    add_info_table(doc, [
        ["设置项", "范围/选项", "说明"],
        ["训练时长", "1-10 分钟", "每个工作回合的训练时间。"],
        ["休息时长", "0-5 分钟，30 秒步进", "每两个训练回合之间的休息时间。"],
        ["回合数", "1-10 回合", "本次训练的总回合数。"],
        ["训练方式", "自由模式 / 跟拍模式", "自由模式不计算节拍分；跟拍模式启用 Perfect/Good/Miss。"],
        ["BPM", "40-140，5 BPM 步进", "跟拍模式下用于节拍评分和训练律动。"],
    ], widths=[3.1, 4.3, 7.4])
    add_h2(doc, "节拍评分")
    add_bullets(doc, [
        "Perfect：击打接近节拍点，约在 ±100ms 范围内。",
        "Good：击打在较宽容的节拍窗口内，约在 ±200ms 范围内。",
        "Miss：节拍窗口内未识别到击打。",
        "自由模式下 BPM 仅作参考，不显示节拍评分。",
    ])
    add_h2(doc, "音效与背景音乐")
    add_picture_if_exists(doc, IMG_SOUND, "设置界面示例：语言、云端音效和背景音乐选择", width_in=4.7)
    add_bullets(doc, [
        "云端音效：可在设置界面试听并选择，训练击打时根据轻击、中击、重击播放反馈音效。",
        "背景音乐：默认“无背景音乐”。用户可选择轻快电子、流行运动、轻摇滚律动等背景音乐。",
        "试听：在设置中试听音效或背景音乐后再保存选择。",
        "训练中若不希望播放音乐，请保持背景音乐为“无背景音乐”。",
    ])

    add_h1(doc, "6. 训练结束、回合战报与云同步")
    add_h2(doc, "训练战报内容")
    add_info_table(doc, [
        ["指标", "说明"],
        ["锻炼时间", "按已完成回合和实际训练时长累计。"],
        ["累计击拳数", "来自蓝牙数据2的有效增量。"],
        ["最大拳击力度", "本次训练识别到的最大力度，单位 N。"],
        ["平均拳击力度", "本次训练有效力度样本的平均值，单位 N。"],
        ["卡路里消耗", "按拳数估算，实时更新并写入战报。"],
        ["等效燃脂数", "由卡路里换算得到的估算值，单位 g。"],
        ["回合明细", "每个回合单独记录时间、拳数、卡路里、等效燃脂和力度。"],
    ], widths=[4.2, 10.6])
    add_h2(doc, "云同步")
    add_bullets(doc, [
        "训练结束后，APP 会将训练会话和回合数据上传到云端。",
        "锻炼成果、榜单排名、个人中心统计会基于云端返回结果更新。",
        "如果网络不稳定，APP 会保留本地显示结果；下次网络恢复后可重新刷新。",
        "手动结束训练时，已完成的回合也会进入训练战报和同步流程。",
    ])

    add_h1(doc, "7. 锻炼成果、榜单排名与个人中心")
    add_picture_if_exists(doc, IMG_ACHIEVEMENTS, "锻炼成果界面示例：段位、徽章和训练统计", width_in=4.7)
    add_h2(doc, "锻炼成果")
    add_body(doc, "锻炼成果页面展示段位、徽章、累计训练表现和最近解锁记录。当前成果围绕以下六类指标建立：锻炼时间、拳击次数、最大拳击力度、平均拳击力度、卡路里消耗、等效燃脂数。")
    add_h2(doc, "榜单排名")
    add_info_table(doc, [
        ["榜单维度", "排名依据"],
        ["锻炼时间", "累计训练时长。"],
        ["拳击次数", "累计有效击打数。"],
        ["最大拳击力度", "训练中记录到的峰值力度。"],
        ["平均拳击力度", "训练中有效力度样本的平均值。"],
        ["卡路里消耗", "训练累计估算卡路里。"],
        ["等效燃脂数", "训练累计估算等效燃脂克数。"],
    ], widths=[4.2, 10.6])
    add_h2(doc, "个人中心")
    add_bullets(doc, [
        "查看昵称、头像、地区、语言和训练概览。",
        "查看隐私政策和用户协议。",
        "通过云端同步展示个人训练总览、段位与成长进度。",
        "头像、昵称等资料可能在榜单或分享内容中显示，请按个人需要设置。",
    ])

    add_h1(doc, "8. 数据口径与计算公式")
    add_h2(doc, "拳击次数")
    add_body(doc, "HitRise 使用智能拳击球蓝牙协议“数据2”的增加量识别拳击次数。训练前、休息时和训练结束后，APP 会关闭陀螺仪计数，避免非训练数据混入。")
    add_h2(doc, "击打力度")
    add_body(doc, "击打力度根据设备蓝牙协议“数据6”和“数据7”组合计算，单位为 N。APP 对传感器生成的力度数据应用 0.6 系数后显示，用于训练反馈和统计分析。")
    add_h2(doc, "卡路里与等效燃脂")
    add_info_table(doc, [
        ["项目", "公式", "示例"],
        ["卡路里", "kcal = 动态 MET x 3.5 x 70kg / 200 x 训练分钟", "动态 MET 由拳频和平均力度估算"],
        ["动态 MET", "4.0-10.5，基准拳击 MET 为 7.0", "拳频越高、平均力度越高，估算消耗越高"],
        ["等效燃脂量", "g = kcal / 7.7", "仅作运动反馈估算"],
    ], widths=[3.3, 6.4, 5.0])
    add_callout(doc, "健康说明", "卡路里和等效燃脂量为运动反馈估算值，不作为医疗、营养诊断或减脂承诺。不同体重、动作幅度、训练强度和设备状态会影响实际消耗。", fill="FFF5E6", border=ORANGE)

    add_h1(doc, "9. 维护建议与常见问题")
    add_h2(doc, "日常维护")
    add_bullets(doc, [
        "训练前检查设备固定状态、绳线磨损和周边空间。",
        "保持设备电量充足；充电时 APP 会显示“充电”，充满后显示“充满”。",
        "手机与设备尽量保持近距离，避免身体、金属支架或其他电子设备长时间遮挡蓝牙信号。",
        "展会或赛事现场建议提前完成连接测试，并准备备用手机或备用设备。",
    ])
    add_h2(doc, "常见问题")
    add_info_table(doc, [
        ["问题", "处理建议"],
        ["扫描不到设备", "确认设备开机、名称为 SENBALL# 开头且末位为英文字母；打开手机蓝牙和权限；靠近后重新扫描。"],
        ["出现蓝牙配对请求", "HitRise 使用免配对 BLE 连接。若系统弹窗出现，请取消；APP 会尝试阻止 SENBALL# 进入配对流程。"],
        ["训练中蓝牙断开", "保持手机靠近设备；等待 APP 自动重连；若长时间未恢复，结束训练后重启蓝牙和设备再连接。"],
        ["第 2 回合无法训练", "请使用最新 APK。新版本已优化休息后重新开启计数的蓝牙写入顺序。"],
        ["电量不显示", "连接后等待设备上报；若仍不显示，断开后重新连接。"],
        ["榜单未刷新", "检查网络连接，稍后下拉刷新或重新打开 APP。"],
        ["音效或音乐不播放", "确认设置中已选择音效或背景音乐，手机媒体音量不为 0。"],
    ], widths=[4.0, 10.8])

    add_callout(
        doc,
        "状态速查",
        "蓝牙图标蓝色表示设备已连接；“读取中”表示正在等待设备上报；“充电/充满”来自设备电量状态；“训练中蓝牙断开，正在自动重连”表示 APP 正在恢复连接；Perfect、Good、Miss 为跟拍模式下的节拍评分。",
        fill=ACCENT_SOFT,
        border=ACCENT,
    )

    section = doc.sections[0]
    header = section.header.paragraphs[0]
    header.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    add_run(header, "HitRise 用户手册", size=8.5, color=MUTED)
    footer = section.footer.paragraphs[0]
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    add_run(footer, "HitRise | 智能拳击速度球训练 APP | 训练数据仅用于运动反馈", size=8.5, color=MUTED)

    doc.save(OUT)


if __name__ == "__main__":
    build_manual()
    print(OUT)

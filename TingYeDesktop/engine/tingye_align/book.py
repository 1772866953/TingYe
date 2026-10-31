from __future__ import annotations

import hashlib
import re
import unicodedata
import zipfile
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from urllib.parse import unquote
import xml.etree.ElementTree as ET


def normalized(text: str) -> str:
    # canonical-v1 deliberately follows the Android reader's UTF-16 char rules.
    return "".join(c.lower()[0] for c in text if ord(c) <= 0xFFFF and
                   (unicodedata.category(c).startswith("L") or unicodedata.category(c) == "Nd"))


@dataclass(frozen=True)
class Sentence:
    start: int
    end: int
    text: str


@dataclass
class Book:
    title: str
    text: str
    digest: str
    sentences: list[Sentence]


SEGMENTATION = "punctuation-v2"


def split_units(section: str):
    """Keep display punctuation and canonical offsets, including comma clauses.
    Quote marks stay with their clause; decimal numbers are not split at a dot.
    """
    start = 0
    i = 0
    boundaries = set("，,；;：:、。！？!?…\n\r—")
    closing = set('”’」』）】》〉"\'')
    while i < len(section):
        char = section[i]
        decimal = char in ".,:，" and i > 0 and i + 1 < len(section) and section[i-1].isdigit() and section[i+1].isdigit()
        if (char in boundaries or char == ".") and not decimal:
            end = i + 1
            while end < len(section) and (section[end] in closing or section[end] in boundaries or section[end] == "."):
                end += 1
            yield section[start:end]
            start = end
            i = end
        else:
            i += 1
    if start < len(section):
        yield section[start:]


def decode(data: bytes) -> str:
    if data.startswith(b"\xff\xfe"):
        return data[2:].decode("utf-16-le")
    if data.startswith(b"\xfe\xff"):
        return data[2:].decode("utf-16-be")
    try:
        return data.decode("utf-8-sig")
    except UnicodeDecodeError:
        return data.decode("gb18030", errors="replace")


def html_text(markup: str, remove_heading: bool = False) -> str:
    from bs4 import BeautifulSoup, NavigableString, Tag
    soup = BeautifulSoup(markup, "html.parser")
    if remove_heading:
        heading = soup.find(["h1", "h2", "h3"])
        if heading:
            heading.decompose()
    root = soup.body or soup
    for element in root.select("script,style,nav,rt,rp,svg,audio,video"):
        element.decompose()
    parts: list[str] = []
    blocks = {"p", "div", "section", "h1", "h2", "h3", "h4", "li", "blockquote", "br", "hr"}

    def visit(node):
        if isinstance(node, NavigableString):
            parts.append(re.sub(r"[\t\r\n ]+", " ", str(node)))
        elif isinstance(node, Tag):
            if node.name in blocks:
                parts.append("\n")
            for child in node.children:
                visit(child)
            if node.name in blocks:
                parts.append("\n")
    visit(root)
    return "\n\n".join(line.strip().replace("\u00a0", " ") for line in "".join(parts).splitlines() if line.strip())


def parse(path: str | Path) -> Book:
    path = Path(path)
    if not path.is_file() or path.stat().st_size > 64 * 1024 * 1024:
        raise ValueError("请选择 64 MB 以内的小说文件")
    sections: list[str] = []
    title = path.stem
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            if len(archive.infolist()) > 20000:
                raise ValueError("EPUB 资源数量过多")
            expanded = 0

            def read(name):
                nonlocal expanded
                entry = archive.getinfo(name)
                if entry.file_size > 8 * 1024 * 1024:
                    raise ValueError("EPUB 正文资源超过 8 MB")
                expanded += entry.file_size
                if expanded > 64 * 1024 * 1024:
                    raise ValueError("EPUB 正文总量超过 64 MB")
                return decode(archive.read(entry))

            container = ET.fromstring(read("META-INF/container.xml"))
            opf_path = next(n.attrib["full-path"] for n in container.iter() if n.tag.split("}")[-1] == "rootfile")
            opf = ET.fromstring(read(opf_path))
            items = {n.attrib["id"]: n for n in opf.iter() if n.tag.split("}")[-1] == "item"}
            title = next(("".join(n.itertext()) for n in opf.iter() if n.tag.split("}")[-1] == "title"), title)
            for ref in (n for n in opf.iter() if n.tag.split("}")[-1] == "itemref"):
                item = items.get(ref.attrib.get("idref"))
                if item is None or ref.attrib.get("linear") == "no" or "nav" in item.attrib.get("properties", "").split():
                    continue
                if item.attrib.get("media-type") not in {"application/xhtml+xml", "text/html"}:
                    continue
                href = unquote(item.attrib["href"].split("#")[0])
                if re.match(r"^[a-zA-Z][a-zA-Z0-9+.-]*:", href) or href.startswith("/") or "\\" in href:
                    raise ValueError("EPUB 正文路径无效")
                parts: list[str] = []
                for part in str(PurePosixPath(opf_path).parent / href).split("/"):
                    if part == "..":
                        if not parts:
                            raise ValueError("EPUB 正文路径越界")
                        parts.pop()
                    elif part not in {"", "."}:
                        parts.append(part)
                text = html_text(read("/".join(parts)), remove_heading=True)
                if text.strip():
                    sections.append(text)
    else:
        source = decode(path.read_bytes()).replace("\r\n", "\n").replace("\r", "\n")
        if path.suffix.lower() in {".html", ".htm", ".xhtml"}:
            source = html_text(source)
        heading = re.compile(r"^(?:第[零〇一二三四五六七八九十百千万两0-9]+[章节回卷部篇].{0,55}|(?:Chapter|CHAPTER)\s+\d+.{0,55}|[序楔][章子]|(?:前言|后记|尾声|番外).{0,25})$")
        body: list[str] = []
        for line in source.splitlines():
            candidate = re.sub(r"^#{1,2} ", "", line.strip()).strip()
            if heading.fullmatch(candidate):
                if "\n".join(body).strip():
                    sections.append("\n".join(body).strip())
                body = []
            else:
                body.append(line.rstrip())
        if "\n".join(body).strip():
            sections.append("\n".join(body).strip())
    sentences: list[Sentence] = []
    total = 0
    for section in sections:
        # Retain punctuation for display; canonical offsets ignore whitespace and punctuation.
        for piece in split_units(section):
            value = normalized(piece)
            if not value:
                continue
            sentences.append(Sentence(total, total + len(value), piece.strip()))
            total += len(value)
    text = "".join(normalized(s) for s in sections)
    if not text or total != len(text):
        raise ValueError("小说没有可匹配正文，或句子划分不完整")
    return Book(title, text, hashlib.sha256(text.encode("utf-8")).hexdigest(), sentences)

"""SentencePiece's native Windows file loader does not handle every Unicode path."""
from pathlib import Path


def enable_unicode_model_paths():
    import sentencepiece
    from funasr.register import tables
    from funasr.tokenizer.sentencepiece_tokenizer import SentencepiecesTokenizer

    class UnicodeSentencepiecesTokenizer(SentencepiecesTokenizer):
        def _build_sentence_piece_processor(self):
            if self.sp is None:
                # Python opens the Unicode filename; the native library reads bytes.
                self.sp = sentencepiece.SentencePieceProcessor(
                    model_proto=Path(self.bpemodel).read_bytes()
                )

    tables.tokenizer_classes["SentencepiecesTokenizer"] = UnicodeSentencepiecesTokenizer

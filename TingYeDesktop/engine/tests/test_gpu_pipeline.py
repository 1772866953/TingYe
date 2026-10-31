import unittest
import numpy as np
from tingye_align.gpu import ctc_path
from tingye_align.audio import Prefetch

class PipelineTests(unittest.TestCase):
    def test_ctc_preserves_blank_between_repeated_characters(self):
        emissions=np.full((5,3),-20,dtype=np.float32)
        # Two identical target symbols need a blank between them.
        emissions[:,0]=[-20,-20,0,-20,0]
        emissions[:,1:]=np.array([[0,0],[0,0],[-20,-20],[0,0],[-20,-20]])
        self.assertEqual(ctc_path(emissions,[7,7]).tolist(),[7,7,0,7,0])

    def test_prefetch_skips_completed_clips_and_closes_early(self):
        closed=[]
        def stream():
            try:
                for i in range(100):yield i,np.zeros(1)
            finally:closed.append(True)
        with Prefetch(stream(),4,{0,2},lambda:False) as batches:
            self.assertEqual([i for i,_ in next(batches)],[1,3,4,5])
        self.assertEqual(closed,[True])

    def test_decoder_error_reaches_consumer_without_hanging(self):
        def stream():
            yield 0,np.zeros(1)
            raise ValueError('decoder failure')
        with self.assertRaisesRegex(ValueError,'decoder failure'):
            with Prefetch(stream(),4,set(),lambda:False) as batches:list(batches)

if __name__=='__main__':unittest.main()

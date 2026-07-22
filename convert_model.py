import torch
from torch import nn
from torch.nn import functional as F
import urllib.request

MODEL_URL = "https://github.com/xinntao/Real-ESRGAN/releases/download/v0.2.5.0/realesr-animevideov3.pth"
PTH_PATH = "realesr-animevideov3.pth"
ONNX_PATH = "realesr-animevideov3.onnx"

class PReLUDecomposed(nn.Module):
    def __init__(self, num_parameters):
        super().__init__()
        self.weight = nn.Parameter(torch.ones(num_parameters) * 0.25)

    def forward(self, x):
        pos = torch.relu(x)
        neg = -torch.relu(-x) * self.weight.view(1, -1, 1, 1)
        return pos + neg

class SRVGGNetCompact(nn.Module):
    def __init__(self, num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16, upscale=4):
        super(SRVGGNetCompact, self).__init__()
        self.upscale = upscale
        self.body = nn.ModuleList()
        self.body.append(nn.Conv2d(num_in_ch, num_feat, 3, 1, 1))
        self.body.append(PReLUDecomposed(num_feat))
        for _ in range(num_conv):
            self.body.append(nn.Conv2d(num_feat, num_feat, 3, 1, 1))
            self.body.append(PReLUDecomposed(num_feat))
        self.body.append(nn.Conv2d(num_feat, num_out_ch * upscale * upscale, 3, 1, 1))
        self.upsampler = nn.PixelShuffle(upscale)

    def forward(self, x):
        out = x
        for layer in self.body:
            out = layer(out)
        out = self.upsampler(out)
        base = F.interpolate(x, scale_factor=self.upscale, mode='nearest')
        out = out + base
        return out

print("Downloading original .pth model...")
urllib.request.urlretrieve(MODEL_URL, PTH_PATH)

print("Building model architecture...")
model = SRVGGNetCompact(num_in_ch=3, num_out_ch=3, num_feat=64, num_conv=16, upscale=4)

print("Loading weights...")
state_dict = torch.load(PTH_PATH, map_location='cpu')
if 'params' in state_dict:
    state_dict = state_dict['params']
elif 'params_ema' in state_dict:
    state_dict = state_dict['params_ema']
model.load_state_dict(state_dict, strict=True)
model.eval()

print("Exporting to ONNX...")
dummy_input = torch.randn(1, 3, 128, 128)
torch.onnx.export(
    model,
    dummy_input,
    ONNX_PATH,
    input_names=['input'],
    output_names=['output'],
    opset_version=11
)
print("Done! Saved to", ONNX_PATH)

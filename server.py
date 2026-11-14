from fastapi import FastAPI
from pydantic import BaseModel
from fastapi.middleware.cors import CORSMiddleware
import uvicorn

app = FastAPI()

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"], 
    allow_methods=["*"],
    allow_headers=["*"],
)

class ChatRequest(BaseModel):
    message: str

@app.post("/api/chat")
async def chat(request: ChatRequest):
    # 这里为了先跑通流程，我们用个简单的假逻辑
    # 明天我们再写真正的代码，去调用你刚刚跑好的 LoRA 模型
    user_text = request.message
    return {"reply": f"我是 Aurora！我收到了你的指令：{user_text}，我的新大脑已经准备好啦！"}

if __name__ == "__main__":
    # 跑在 8000 端口
    uvicorn.run(app, host="0.0.0.0", port=8000)

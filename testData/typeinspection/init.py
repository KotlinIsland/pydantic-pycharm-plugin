

from pydantic import BaseModel


class A(BaseModel):
    a: int

    def __init__(self, a):
        super().__init__(a=a)


A(a=int(123))

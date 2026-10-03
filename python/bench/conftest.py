"""Fixtures de los micro-benchmarks. Cada test se llama
test_<métrica>[<estructura>-<N>] y guarda en extra_info cómo convertir su
tiempo en la métrica del SPEC; bench/run.py hace la conversión al CSV."""

import pytest

from bench.benchkit import Env, remove


class Fixture:
    """Lo que cuesta preparar (un datalake, una base o un índice con N libros)
    se guarda aquí y se suelta cuando cambia la estructura o el tamaño."""

    def __init__(self, env: Env):
        self.env = env
        self.key = None
        self.value = None
        self.closers = []

    def get(self, key, build):
        if self.key != key:
            self.release()
            self.key, self.value = key, build(self.env.work / "fixture")
        return self.value

    def release(self):
        for close in self.closers:
            close()
        self.closers = []
        self.key = self.value = None
        remove(self.env.work / "fixture")


@pytest.fixture(scope="session")
def env():
    return Env()


@pytest.fixture(scope="session")
def fixture(env):
    slot = Fixture(env)
    yield slot
    slot.release()


@pytest.fixture
def record(benchmark):
    """Anota en el JSON de pytest-benchmark la fila del CSV que corresponde.
    convert: "ms", "us" (tiempo por operación) o "throughput" (n / segundos)."""

    def annotate(component: str, structure: str, n: int, metric: str, unit: str, convert: str):
        benchmark.extra_info.update(component=component, structure=structure, n_books=n,
                                    metric=metric, unit=unit, convert=convert)

    return annotate

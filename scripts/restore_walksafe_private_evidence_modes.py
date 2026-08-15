#!/usr/bin/env python3
"""Restore Git-unrepresentable private evidence bytes and modes."""

from __future__ import annotations

import argparse
import base64
import hashlib
import os
from pathlib import Path
import stat
import zlib


PRIVATE_EVENT_DIRECTORIES = (
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-WORK-SESSION-RESUMED-FP008-20260809-005"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-20260809-005"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-20260812-006"
    ),
    Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001"
    ),
)
PRIVATE_LOG_DIRECTORY = Path(
    "docs/control/execution/goal-results/WS-GOAL-EPIC-04-FP-022-R001/logs"
)

# These three logs are intentionally ignored by Git and excluded from the managed
# snapshot.  Their sealed bytes are kept here so a clean checkout can reconstruct
# the physical-only evidence before validation.
PRIVATE_LOG_PAYLOADS = (
    (
        "android-user-internal.log",
        3701,
        "cdf1a98a74eb416ed30689d71e509f44155596ceb8d05a3df9c211014c904838",
        "".join(
            (
                "c-oa$ZExZ@5dO}uuyoSuPPc(TOSMJnNm&wlZJ|XZZ0|lHlMKO2Vn?>q(*5-",
                "}d7*{&^3qQj=Xu8C$K&zLqcy#At%<Xm%+0}IHGY`Qtb3dM?gxY>iRw`vNWlW~<qNr9SoYLeEnR1^`ukz#T=ljhhF",
                "%beH4Ry>8%i2PK)StR@JtLtY&H?&fFmx9fI=)dj$^tvo`u|jR9Ag6!gWR(l(u1=hD*-",
                "0hkuG`suaZQ2=Y(G6xu70e{>#CeRn-",
                ";;W_ro^2op(To~pzqkr!8&qwDMqyDhlA6yQ<_5U<4N2b~F1lbBMQc3g{$nB3AQJ^*Bp&}cRO)^E+DT`1AqHsC_BU",
                "M1`Vhf2BJ}5<=Srm~qWRx8QT{1yxEF=)bWG23nBm#;&2^&DGxTw~N8ufaC@Kq^TSIDq;**)v_d$@!QQx-re;i{0V",
                "yVY^@n*x6nFh$p>K)6D;%ddp-",
                "d=uA&{Af9iforN6GCQTm2Tzq2NdmYlopXQ(L~Qb5vD?mH#G`6gNS#Rdp3g4lhWB9Dmgl_xo}WZ5C<G3Y>LcV>@C^",
                "%F;TNFrj8N-",
                "J7pT9ZVOwx<Y&rOzKCv*@sKpvm6)Q}IPoqZs=?*3g52XU>i#TCrbl(Y$2U~^wNuTIk!uW{L#^^259+}=|zkVfQ3;",
                "r&LTU5?K(|~GP1&(QwL)}~h7xbL!t!m@TClrtI8nir9U&2A^OdPb2>B7ZX;4{kE22)8T7xr3GAF~N0Q^t3yR@UR-",
                "nNjv?y&eAZSj;8!BDmYI8g*KyJSY!h9A#-$d18@r&0@Hv@-wV525VS{yl}|nwfW&VL=J>c--Xv#;}eUV-",
                "i`YQ!+lsT#?=yMxJA3<a=b(h<>RB*cpvUDz@LiY>rMTC*H5yLg-",
                "54bT(^2$rlLITu^t+LN<%7h<Rn)<!M6su&!C30&m#q7?L~a^_Bz7KBXVCjzF(U8E%sL)NPbnC)1jRv5%W>QGWKij",
                "J9|9KeY59Nwr+)+OYsx@b~cN>afY@hA`?)xjU;4LVwIMxNcKg>e~H&3szg80h+#N_%R-",
                "I?O0Cn+k{tpwNt#}qH*jgXs2_gdF7YjCRT;E+Jgj6lWh$!xvv6HNz$B_)MLexb8`7Xvl+bUP(7n5v&mFtwy1p^+V",
                "bU`ZK-BPvVYq}Tl|klxyUHZ9ynsZ=diy7wL!xT*Dyz~hPNSD!^%}j$9qqg=r73wvulsEDBnNal-",
                "<LO2o4CvI*m2#-",
                "a++1x=c+S2BPcY+9G2ifG&0qQ^a<?2Pc;Nz$#6i@#L!u%qoIRgs<4!oSY#J9JMr_tO=VsL5uakF$$A2n09NE_a@2",
                "K<S~m(OHdndL)nUQBZ}`w9EwRH}{7aH7hJ&lVX_{n1F=~P<lYo>0n=+HA9n1QJEAu_w`RB&NF41<#$4~cmRrht-",
                "y)e&)=2_W&{RbQ{*-`",
            )
        ),
    ),
    (
        "backend-navigation-internal.log",
        531,
        "249031160869dc49edbec23a88808d17dadda9e174a1d5e97a0568361123d951",
        "".join(
            (
                "c-oCo!EVAZ488LezU(xmNlRg)5{E*IK-",
                "*dbnHZX;Dzt${v`Ll#6Mw&U;<BMldk4#Y_LKeORrvU@3@33mnG?^;#_7{jm_#sN(`lMSX+l?v8I4D48jsT`9=ey#",
                "Jfzd11GrQ*$3>wwY$vc&shm~VmdtQmGAWKVDhy}!QR5F*?Je7KwBwQ+R-",
                "s%U(TN+eJtL2JeRAja@J2|i&xcY;KqEjkGzGT@KpLb9a+a4|8+8%|H-",
                "O$@!}2|s1#V~TSIi_ki5)u#B{QRr4dv)_XZ79Vl7<T!M_EXr-HgaRa((Lhf!_~Y-",
                "|4#DKIz_)K|n~y`8VKiyt;(kymfpJG_#f$AS4i{N9@&g&&>xG>&w`%5p0>Lnv4K9Mj6{Ab}zJUsh{HbWk$31QE=T",
                "#qTf9C9M>B(_gy@H0-TnWT>",
            )
        ),
    ),
    (
        "test-layer-registry-validate.log",
        421,
        "e6502409d2de507ce23ef8dff787545a5b835d3862f3f1f362a326df71190e02",
        "".join(
            (
                "c-noCQE!4U6oudY6@6c7TY@-",
                "AeCS5q$P^89b38Q_sx%@rEx7pa#bkTfO!jti@44rGIa|7X%jklM#RhwxcwVnoG>O0_=dbHTJjKZzRqaWkvTS$qpw",
                "YN)3t1yqNvlvL8{MB^X_Y+n27Sr;=;U6(gKCtOH7raI&&ulk1$x*E@RUr@PB+M0x~gr!PPz)X!fMkwWcx<^1c*+#",
                "PSo<EtP@3Vt!ldQ;62M)m$Fl%?QiOg)0DGFP!4W?Aby0z=fn^FASAPKfCCat9`G#0cr@ZH<6=p3mWq_U#2HU>@j;",
                "hyL^%t=hGyAa{QFQ!ngb)9Q{|k1z0~y}Fi_UE)(yfPwK2ER-&^(>^P$y<f%{bw{liSA0rtO{ug1rKdH",
            )
        ),
    ),
)


class ModeRestoreError(RuntimeError):
    pass


def _open_directory(root: Path, relative: Path, *, create_leaf: bool = False) -> int:
    flags = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW
    current_fd = os.open(root, flags)
    try:
        for index, part in enumerate(relative.parts):
            if create_leaf and index == len(relative.parts) - 1:
                try:
                    os.mkdir(part, mode=0o700, dir_fd=current_fd)
                except FileExistsError:
                    pass
            next_fd = os.open(part, flags, dir_fd=current_fd)
            os.close(current_fd)
            current_fd = next_fd
        return current_fd
    except BaseException:
        os.close(current_fd)
        raise


def _read_all(file_fd: int) -> bytes:
    chunks: list[bytes] = []
    while True:
        chunk = os.read(file_fd, 64 * 1024)
        if not chunk:
            return b"".join(chunks)
        chunks.append(chunk)


def _sealed_log_bytes(name: str, byte_count: int, sha256: str, encoded: str) -> bytes:
    try:
        raw = zlib.decompress(base64.b85decode(encoded.encode("ascii")))
    except (ValueError, zlib.error) as exc:
        raise ModeRestoreError(f"embedded private evidence is invalid: {name}") from exc
    if len(raw) != byte_count or hashlib.sha256(raw).hexdigest() != sha256:
        raise ModeRestoreError(f"embedded private evidence seal differs: {name}")
    return raw


def _restore_private_logs(root: Path) -> tuple[int, int]:
    try:
        directory_fd = _open_directory(root, PRIVATE_LOG_DIRECTORY, create_leaf=True)
    except OSError as exc:
        raise ModeRestoreError(f"cannot open private log directory {PRIVATE_LOG_DIRECTORY}: {exc}") from exc
    try:
        expected_names = {row[0] for row in PRIVATE_LOG_PAYLOADS}
        if set(os.listdir(directory_fd)) - expected_names:
            raise ModeRestoreError(f"unexpected private evidence entry in {PRIVATE_LOG_DIRECTORY}")
        file_count = 0
        for name, byte_count, sha256, encoded in PRIVATE_LOG_PAYLOADS:
            expected = _sealed_log_bytes(name, byte_count, sha256, encoded)
            created = False
            try:
                file_fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
            except FileNotFoundError:
                try:
                    file_fd = os.open(
                        name,
                        os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                        0o600,
                        dir_fd=directory_fd,
                    )
                    created = True
                    view = memoryview(expected)
                    while view:
                        written = os.write(file_fd, view)
                        if written <= 0:
                            raise ModeRestoreError(f"cannot write private evidence file: {PRIVATE_LOG_DIRECTORY / name}")
                        view = view[written:]
                    os.fsync(file_fd)
                    os.close(file_fd)
                    file_fd = os.open(name, os.O_RDONLY | os.O_NOFOLLOW, dir_fd=directory_fd)
                except Exception:
                    if created:
                        try:
                            os.close(file_fd)
                        except OSError:
                            pass
                    raise
            except OSError as exc:
                raise ModeRestoreError(
                    f"cannot open private evidence file {PRIVATE_LOG_DIRECTORY / name}: {exc}"
                ) from exc
            try:
                file_stat = os.fstat(file_fd)
                if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_nlink != 1:
                    raise ModeRestoreError(
                        "private evidence entry must be a single-link regular file: "
                        f"{PRIVATE_LOG_DIRECTORY / name}"
                    )
                if file_stat.st_uid != os.geteuid():
                    raise ModeRestoreError(
                        f"private evidence owner differs: {PRIVATE_LOG_DIRECTORY / name}"
                    )
                raw = _read_all(file_fd)
                if raw != expected:
                    raise ModeRestoreError(
                        f"private evidence content differs: {PRIVATE_LOG_DIRECTORY / name}"
                    )
                os.fchmod(file_fd, 0o600)
                if stat.S_IMODE(os.fstat(file_fd).st_mode) != 0o600:
                    raise ModeRestoreError(
                        f"private evidence file mode restore failed: {PRIVATE_LOG_DIRECTORY / name}"
                    )
                file_count += 1
            finally:
                os.close(file_fd)
        if set(os.listdir(directory_fd)) != expected_names:
            raise ModeRestoreError(f"private evidence file set differs in {PRIVATE_LOG_DIRECTORY}")
        os.fchmod(directory_fd, 0o700)
        if stat.S_IMODE(os.fstat(directory_fd).st_mode) != 0o700:
            raise ModeRestoreError(f"private directory mode restore failed: {PRIVATE_LOG_DIRECTORY}")
        return 1, file_count
    finally:
        os.close(directory_fd)


def restore_modes(root: Path) -> tuple[int, int]:
    root = root.resolve(strict=True)
    directory_count = 0
    file_count = 0
    file_flags = os.O_RDONLY | os.O_NOFOLLOW
    for relative in PRIVATE_EVENT_DIRECTORIES:
        try:
            directory_fd = _open_directory(root, relative)
        except OSError as exc:
            raise ModeRestoreError(f"cannot open private directory {relative}: {exc}") from exc
        file_fds: list[tuple[str, int]] = []
        try:
            directory_stat = os.fstat(directory_fd)
            if not stat.S_ISDIR(directory_stat.st_mode):
                raise ModeRestoreError(f"private path is not a directory: {relative}")
            names = sorted(os.listdir(directory_fd))
            if not names:
                raise ModeRestoreError(f"private directory is empty: {relative}")
            for name in names:
                if not name or name in {".", ".."} or "/" in name:
                    raise ModeRestoreError(f"invalid private evidence entry in {relative}")
                try:
                    file_fd = os.open(name, file_flags, dir_fd=directory_fd)
                except OSError as exc:
                    raise ModeRestoreError(
                        f"cannot open private evidence file {relative / name}: {exc}"
                    ) from exc
                file_stat = os.fstat(file_fd)
                if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_nlink != 1:
                    os.close(file_fd)
                    raise ModeRestoreError(
                        f"private evidence entry must be a single-link regular file: {relative / name}"
                    )
                file_fds.append((name, file_fd))

            os.fchmod(directory_fd, 0o700)
            if stat.S_IMODE(os.fstat(directory_fd).st_mode) != 0o700:
                raise ModeRestoreError(f"private directory mode restore failed: {relative}")
            for name, file_fd in file_fds:
                os.fchmod(file_fd, 0o600)
                after = os.fstat(file_fd)
                if stat.S_IMODE(after.st_mode) != 0o600 or after.st_nlink != 1:
                    raise ModeRestoreError(
                        f"private evidence file mode restore failed: {relative / name}"
                    )
                file_count += 1
            directory_count += 1
        finally:
            for _, file_fd in file_fds:
                os.close(file_fd)
            os.close(directory_fd)
    log_directory_count, log_file_count = _restore_private_logs(root)
    return directory_count + log_directory_count, file_count + log_file_count


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        directories, files = restore_modes(args.root)
    except (ModeRestoreError, OSError) as exc:
        print(f"ERROR: {exc}")
        return 1
    print(f"PASS: private evidence modes restored (directories={directories}, files={files})")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())

"""Strict Gateway-facing contracts for email account enrollment."""

from __future__ import annotations

from datetime import UTC, date, datetime
import re
from typing import Annotated, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    StrictBool,
    StringConstraints,
    field_serializer,
    field_validator,
    model_validator,
)


_DATE_OF_BIRTH = re.compile(r"^\d{4}-\d{2}-\d{2}$")
_EMAIL_LOCAL = re.compile(
    r"[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+"
    r"(?:\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*"
)
_DOMAIN_LABEL = re.compile(r"[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?")


def canonical_email(value: str) -> str:
    """Validate the exact ASCII wire form; the Gateway owns IDN conversion."""

    if not 3 <= len(value) <= 254 or value.count("@") != 1:
        raise ValueError("invalid email address")
    local, raw_domain = value.rsplit("@", 1)
    if not local or len(local) > 64 or _EMAIL_LOCAL.fullmatch(local) is None:
        raise ValueError("invalid email address")
    if not raw_domain or raw_domain.endswith("."):
        raise ValueError("invalid email address")
    try:
        raw_domain.encode("ascii")
    except UnicodeEncodeError as exc:
        raise ValueError("invalid email address") from exc
    domain = raw_domain.lower()
    labels = domain.split(".")
    if (
        raw_domain != domain
        or len(domain) > 253
        or len(labels) < 2
        or any(
            _DOMAIN_LABEL.fullmatch(label) is None
            or (label[2:4] == "--" and not label.startswith("xn--"))
            for label in labels
        )
    ):
        raise ValueError("invalid email address")
    canonical = f"{local}@{domain}"
    if len(canonical) > 254:
        raise ValueError("invalid email address")
    return canonical


def require_canonical_email(value: object) -> object:
    if type(value) is not str:
        return value
    try:
        canonical = canonical_email(value)
        canonical.encode("ascii")
    except (UnicodeEncodeError, ValueError) as exc:
        raise ValueError("email must use the canonical ASCII account contract") from exc
    if value != canonical:
        raise ValueError("email must use the canonical ASCII account contract")
    return canonical

EnrollmentRequestId = Annotated[
    str,
    StringConstraints(
        min_length=16,
        max_length=128,
        pattern=r"^[A-Za-z0-9_-]{16,128}$",
    ),
]
EmailAddress = Annotated[str, StringConstraints(min_length=3, max_length=254)]
EnrollmentHandle = Annotated[
    str,
    StringConstraints(
        min_length=43,
        max_length=43,
        pattern=r"^[A-Za-z0-9_-]{43}$",
    ),
]
OtpCode = Annotated[str, StringConstraints(pattern=r"^[0-9]{6}$")]
AccountPassword = Annotated[str, StringConstraints(min_length=10, max_length=128)]
DocumentVersion = Annotated[
    str,
    StringConstraints(
        min_length=1,
        max_length=64,
        pattern=r"^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$",
    ),
]
CanonicalActorId = Annotated[
    str,
    StringConstraints(
        min_length=36,
        max_length=36,
        pattern=(
            r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
            r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
        ),
    ),
]
LowerSha256 = Annotated[str, StringConstraints(pattern=r"^[0-9a-f]{64}$")]


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EmailOtpEnrollmentRequestV1(_StrictModel):
    schema_version: Literal["walksafe.account-enrollment-email-otp.v1"]
    email: EmailAddress
    date_of_birth: date
    request_id: EnrollmentRequestId

    @field_validator("email", mode="before")
    @classmethod
    def require_ascii_email(cls, value: object) -> object:
        return require_canonical_email(value)

    @field_validator("date_of_birth", mode="before")
    @classmethod
    def require_canonical_calendar_date(cls, value: object) -> object:
        if type(value) is not str or _DATE_OF_BIRTH.fullmatch(value) is None:
            raise ValueError("date_of_birth must be YYYY-MM-DD")
        return value


class EmailOtpEnrollmentResponseV1(_StrictModel):
    schema_version: Literal[
        "walksafe.account-enrollment-email-otp-response.v1"
    ]
    enrollment_handle: EnrollmentHandle
    expires_at: AwareDatetime
    resend_available_at: AwareDatetime

    @field_serializer("expires_at", "resend_available_at")
    def canonical_utc_time(self, value: datetime) -> str:
        return value.astimezone(UTC).strftime("%Y-%m-%dT%H:%M:%SZ")


class SignupDocumentVersionsV1(_StrictModel):
    terms_of_service: DocumentVersion
    privacy_notice: DocumentVersion
    location_terms: DocumentVersion
    raw_original: DocumentVersion
    automatic_reporting: DocumentVersion
    training_reuse: DocumentVersion


class SignupConsentSelectionsV1(_StrictModel):
    terms_of_service: StrictBool
    privacy_notice: StrictBool
    location_terms: StrictBool
    raw_original: StrictBool = False
    automatic_reporting: StrictBool = False
    training_reuse: StrictBool = False

    @model_validator(mode="after")
    def require_mandatory_selections(self) -> "SignupConsentSelectionsV1":
        if not (
            self.terms_of_service
            and self.privacy_notice
            and self.location_terms
        ):
            raise ValueError("all mandatory signup selections must be true")
        return self


class SignupConsentV1(_StrictModel):
    schema_version: Literal["walksafe.signup-consent.v1"]
    document_versions: SignupDocumentVersionsV1
    selections: SignupConsentSelectionsV1


class AccountCreateRequestV1(_StrictModel):
    schema_version: Literal["walksafe.account-create.v1"]
    enrollment_handle: EnrollmentHandle
    otp_code: OtpCode
    password: AccountPassword
    consent: SignupConsentV1


class AccountResponseV1(_StrictModel):
    schema_version: Literal["walksafe.account.v1"]
    actor_id: CanonicalActorId
    account_generation: int = Field(ge=1, le=9_007_199_254_740_991)
    signup_receipt_sha256: LowerSha256


class AccountAuthenticateRequestV1(_StrictModel):
    schema_version: Literal["walksafe.account-authenticate.v1"]
    email: EmailAddress
    password: AccountPassword

    @field_validator("email", mode="before")
    @classmethod
    def require_ascii_email(cls, value: object) -> object:
        return require_canonical_email(value)


class AccountAuthenticationResponseV1(_StrictModel):
    schema_version: Literal["walksafe.account-authentication.v1"]
    actor_id: CanonicalActorId
    account_generation: int = Field(ge=1, le=9_007_199_254_740_991)
    auth_epoch: int = Field(ge=1, le=9_007_199_254_740_991)


class AccountErrorDetailV1(_StrictModel):
    code: str = Field(min_length=1, max_length=64, pattern=r"^[a-z][a-z0-9_]*$")
    message: str = Field(min_length=1, max_length=240)


class AccountErrorResponseV1(_StrictModel):
    detail: AccountErrorDetailV1


__all__ = [
    "AccountAuthenticateRequestV1",
    "AccountAuthenticationResponseV1",
    "AccountCreateRequestV1",
    "AccountErrorResponseV1",
    "AccountResponseV1",
    "canonical_email",
    "EmailOtpEnrollmentRequestV1",
    "EmailOtpEnrollmentResponseV1",
    "require_canonical_email",
    "SignupConsentV1",
    "SignupDocumentVersionsV1",
]

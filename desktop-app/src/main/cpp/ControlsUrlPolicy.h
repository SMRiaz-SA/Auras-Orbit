#pragma once

#include <cstddef>
#include <cwctype>
#include <string>

namespace auras {

inline std::wstring normalizeControlsUrl(std::wstring value) {
    if (value.rfind(L"file:/", 0) != 0) return L"";

    std::size_t start = 5;
    while (start < value.size() && value[start] == L'/') ++start;
    value = L"file:///" + value.substr(start);
    for (auto& character : value) character = static_cast<wchar_t>(std::towlower(character));
    return value;
}

inline bool isControlsUiUrl(const std::wstring& candidate, const std::wstring& controlsUrl) {
    const auto normalizedCandidate = normalizeControlsUrl(candidate);
    const auto normalizedControlsUrl = normalizeControlsUrl(controlsUrl);
    return !normalizedCandidate.empty() && normalizedCandidate == normalizedControlsUrl;
}

inline bool startsWithInsensitive(const std::wstring& value, const std::wstring& prefix) {
    if (value.size() < prefix.size()) return false;
    for (std::size_t i = 0; i < prefix.size(); ++i) {
        if (std::towlower(value[i]) != std::towlower(prefix[i])) return false;
    }
    return true;
}

inline bool isUuid(const std::wstring& value) {
    if (value.size() != 36) return false;
    for (std::size_t i = 0; i < value.size(); ++i) {
        const bool separator = i == 8 || i == 13 || i == 18 || i == 23;
        if (separator) {
            if (value[i] != L'-') return false;
        } else if (!((value[i] >= L'0' && value[i] <= L'9') ||
                     (std::towlower(value[i]) >= L'a' && std::towlower(value[i]) <= L'f'))) {
            return false;
        }
    }
    return true;
}

inline bool isYouTubeId(const std::wstring& value) {
    if (value.size() != 11) return false;
    for (const auto character : value) {
        if (!((character >= L'a' && character <= L'z') ||
              (character >= L'A' && character <= L'Z') ||
              (character >= L'0' && character <= L'9') ||
              character == L'_' || character == L'-')) {
            return false;
        }
    }
    return true;
}

inline bool isFormEncodedValue(const std::wstring& value) {
    if (value.empty()) return false;
    for (std::size_t i = 0; i < value.size(); ++i) {
        const auto character = value[i];
        const bool unescaped =
            (character >= L'a' && character <= L'z') ||
            (character >= L'A' && character <= L'Z') ||
            (character >= L'0' && character <= L'9') ||
            character == L'.' || character == L'-' || character == L'*' || character == L'_' ||
            character == L'+';
        if (unescaped) continue;
        if (character != L'%' || i + 2 >= value.size()) return false;
        const auto isHex = [](wchar_t digit) {
            return (digit >= L'0' && digit <= L'9') ||
                (std::towlower(digit) >= L'a' && std::towlower(digit) <= L'f');
        };
        if (!isHex(value[i + 1]) || !isHex(value[i + 2])) return false;
        i += 2;
    }
    return true;
}

// Trailer pages are served by the app's capability-protected loopback proxy.
// Keep this allowlist narrow so the native WebView cannot navigate to arbitrary sites.
inline bool isLocalTrailerUrl(const std::wstring& value) {
    constexpr wchar_t prefix[] = L"http://127.0.0.1:";
    constexpr std::size_t prefixLength = (sizeof(prefix) / sizeof(prefix[0])) - 1;
    if (!startsWithInsensitive(value, prefix)) return false;

    const auto pathStart = value.find(L"/trailer?", prefixLength);
    if (pathStart == std::wstring::npos) return false;

    const auto portText = value.substr(prefixLength, pathStart - prefixLength);
    if (portText.empty() || portText.size() > 5) return false;
    unsigned int port = 0;
    for (const auto digit : portText) {
        if (digit < L'0' || digit > L'9') return false;
        port = port * 10 + static_cast<unsigned int>(digit - L'0');
        if (port > 65535) return false;
    }
    if (port == 0) return false;

    const auto query = value.substr(pathStart + 9); // length of "/trailer?"
    const auto separator = query.find(L'&');
    if (separator == std::wstring::npos || query.find(L'#') != std::wstring::npos) return false;

    const auto capability = query.substr(4, separator >= 4 ? separator - 4 : 0);
    if (query.rfind(L"cap=", 0) != 0 || !isUuid(capability)) return false;

    const auto payload = query.substr(separator + 1);
    if (payload.rfind(L"id=", 0) == 0) {
        return isYouTubeId(payload.substr(3));
    }
    if (payload.rfind(L"u=", 0) == 0) {
        return isFormEncodedValue(payload.substr(2));
    }
    return false;
}

inline std::wstring normalizeLocalTrailerUrl(std::wstring value) {
    if (!isLocalTrailerUrl(value)) return L"";
    for (std::size_t i = 0; i < 7; ++i) {
        value[i] = static_cast<wchar_t>(std::towlower(value[i]));
    }
    return value;
}

inline bool isTrustedUiUrl(const std::wstring& candidate, const std::wstring& activeUiUrl) {
    if (isControlsUiUrl(candidate, activeUiUrl)) return true;
    const auto normalizedCandidate = normalizeLocalTrailerUrl(candidate);
    const auto normalizedActiveUiUrl = normalizeLocalTrailerUrl(activeUiUrl);
    return !normalizedCandidate.empty() && normalizedCandidate == normalizedActiveUiUrl;
}

} // namespace auras

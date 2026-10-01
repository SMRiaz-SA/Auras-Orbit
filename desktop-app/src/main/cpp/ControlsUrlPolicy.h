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

} // namespace auras

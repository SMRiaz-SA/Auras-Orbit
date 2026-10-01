#include "ControlsUrlPolicy.h"

#include <cassert>
#include <string>

int main() {
    const std::wstring controls = L"file:///D:/Auras%20Orbit/player.html";

    assert(auras::isControlsUiUrl(L"file:/D:/Auras%20Orbit/player.html", controls));
    assert(auras::isControlsUiUrl(L"file:///d:/auras%20orbit/PLAYER.HTML", controls));
    assert(!auras::isControlsUiUrl(L"https://attacker.example/player.html", controls));
    assert(!auras::isControlsUiUrl(L"file:///D:/Auras%20Orbit/other.html", controls));
    assert(!auras::isControlsUiUrl(L"file:///D:/Auras%20Orbit/player.html.evil", controls));
    assert(!auras::isControlsUiUrl(L"file:///D:/Auras%20Orbit/player.html", L""));
    assert(!auras::isControlsUiUrl(L"https://attacker.example/player.html", L"https://attacker.example/player.html"));

    return 0;
}

#ifndef SANISIZER_UTILS_HPP
#define SANISIZER_UTILS_HPP

#include <type_traits>

namespace sanisizer {

template<typename Input_>
using I = std::remove_cv_t<std::remove_reference_t<Input_> >;

template<typename Integer_>
constexpr auto as_unsigned(Integer_ x) {
    static_assert(std::is_integral<Integer_>::value);
    if constexpr(std::is_unsigned<Integer_>::value) {
        return x;
    } else {
        return static_cast<typename std::make_unsigned<Integer_>::type>(x);
    }
}

}

#endif

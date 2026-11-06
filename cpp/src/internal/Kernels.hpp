#pragma once
#include <opencv2/core.hpp>
#include <span>
#include <vector>
namespace document_scanner::internal {
void ink(const std::vector<cv::Mat*>& channels, const std::vector<cv::Mat*>& blurred,
         const cv::Mat* ink, const cv::Mat& deepen, const cv::Mat& amount);
void gain(cv::Mat& rgba, const cv::Mat& gain);
void divide(const cv::Mat& source, const std::vector<cv::Mat*>& into, const cv::Mat& level, const float* scales);
void grayDivide(const cv::Mat& source, cv::Mat& into, const cv::Mat& level,
                const float* weights, const float* gains, float scale);
void remap(const cv::Mat& source, cv::Mat& into, const double* inverse,
           const float* left, const float* right, const float* top, const float* bottom);
}
